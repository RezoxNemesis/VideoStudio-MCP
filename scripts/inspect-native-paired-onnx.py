#!/usr/bin/env python3
"""Read bounded ONNX protobuf graph metadata; never execute a model or process artwork.

Weights stay on disk: this inspector seeks over tensor payloads and records only
public graph signatures, operators, initializer identities and input usage.
"""
import argparse
import collections
import io
import json
from pathlib import Path


def varint(stream):
    value = 0
    for shift in range(0, 70, 7):
        byte = stream.read(1)
        if not byte:
            raise ValueError('Truncated protobuf varint')
        value |= (byte[0] & 127) << shift
        if byte[0] < 128:
            return value
    raise ValueError('Oversize protobuf varint')


def fields(stream, end):
    while stream.tell() < end:
        key = varint(stream)
        number, wire = key >> 3, key & 7
        if number == 0:
            raise ValueError('Invalid protobuf field zero')
        if wire == 0:
            yield number, wire, varint(stream)
        elif wire in (1, 5):
            length = 8 if wire == 1 else 4
            start = stream.tell()
            if start + length > end:
                raise ValueError('Protobuf fixed field exceeds parent')
            yield number, wire, (start, length)
            stream.seek(start + length)
        elif wire == 2:
            length = varint(stream)
            start = stream.tell()
            if start + length > end:
                raise ValueError('Protobuf length exceeds parent')
            yield number, wire, (start, length)
            stream.seek(start + length)
        else:
            raise ValueError('Unsupported protobuf wire type ' + str(wire))
    if stream.tell() != end:
        raise ValueError('Protobuf parent length mismatch')


def payload(stream, pointer, limit=1024 * 1024):
    start, length = pointer
    if length > limit:
        raise ValueError('Metadata payload exceeds bound')
    stream.seek(start)
    value = stream.read(length)
    if len(value) != length:
        raise ValueError('Truncated protobuf payload')
    return value


def message(data):
    stream = io.BytesIO(data)
    for number, wire, value in fields(stream, len(data)):
        yield number, wire, payload(stream, value) if wire == 2 else value


def value_info(data):
    result = {}
    for number, wire, value in message(data):
        if number == 1 and wire == 2:
            result['name'] = value.decode('utf-8')
        elif number == 2 and wire == 2:
            for kind, kind_wire, tensor in message(value):
                if kind != 1 or kind_wire != 2:
                    result['unsupportedTypeField'] = kind
                    continue
                for field, tensor_wire, item in message(tensor):
                    if field == 1 and tensor_wire == 0:
                        result['elementType'] = item
                    elif field == 2 and tensor_wire == 2:
                        dimensions = []
                        for dim, dim_wire, descriptor in message(item):
                            if dim != 1 or dim_wire != 2:
                                continue
                            dimension = None
                            for tag, tag_wire, dimension_value in message(descriptor):
                                if tag == 1 and tag_wire == 0:
                                    dimension = dimension_value
                                elif tag == 2 and tag_wire == 2:
                                    dimension = dimension_value.decode('utf-8')
                            dimensions.append(dimension)
                        result['shape'] = dimensions
    return result


def inspect(path):
    result = {'path': str(path), 'fileBytes': path.stat().st_size}
    operators, uses, initializer_types = collections.Counter(), collections.Counter(), collections.Counter()
    initializers = set()
    result.update(inputs=[], outputs=[], externalData=[], opsets=[])
    with path.open('rb') as stream:
        for field, wire, pointer in fields(stream, result['fileBytes']):
            if field == 1 and wire == 0:
                result['irVersion'] = pointer
            elif field in (2, 3) and wire == 2:
                result['producerName' if field == 2 else 'producerVersion'] = payload(stream, pointer, 4096).decode('utf-8')
            elif field == 8 and wire == 2:
                opset = {}
                for tag, tag_wire, value in message(payload(stream, pointer)):
                    if tag == 1 and tag_wire == 2:
                        opset['domain'] = value.decode('utf-8')
                    elif tag == 2 and tag_wire == 0:
                        opset['version'] = value
                result['opsets'].append(opset)
            elif field == 7 and wire == 2:
                start, length = pointer
                stream.seek(start)
                for tag, tag_wire, value in fields(stream, start + length):
                    if tag in (11, 12) and tag_wire == 2:
                        result['inputs' if tag == 11 else 'outputs'].append(value_info(payload(stream, value)))
                    elif tag == 1 and tag_wire == 2:
                        node_start, node_length = value
                        stream.seek(node_start)
                        operator, domain = '', ''
                        for node_tag, node_wire, node_value in fields(stream, node_start + node_length):
                            if node_wire != 2:
                                continue
                            if node_tag == 1:
                                uses[payload(stream, node_value, 65536).decode('utf-8')] += 1
                            elif node_tag in (4, 7):
                                text = payload(stream, node_value, 4096).decode('utf-8')
                                if node_tag == 4:
                                    operator = text
                                else:
                                    domain = text
                        operators[domain + '::' + operator] += 1
                    elif tag == 5 and tag_wire == 2:
                        tensor_start, tensor_length = value
                        stream.seek(tensor_start)
                        name, location, external = '', 0, []
                        for tensor_tag, tensor_wire, tensor_value in fields(stream, tensor_start + tensor_length):
                            if tensor_tag == 2 and tensor_wire == 0:
                                initializer_types[tensor_value] += 1
                            elif tensor_tag == 8 and tensor_wire == 2:
                                name = payload(stream, tensor_value, 65536).decode('utf-8')
                            elif tensor_tag == 14 and tensor_wire == 0:
                                location = tensor_value
                            elif tensor_tag == 13 and tensor_wire == 2:
                                external.append(payload(stream, tensor_value, 65536).decode('utf-8', errors='replace'))
                        initializers.add(name)
                        if location or external:
                            result['externalData'].append({'name': name, 'location': location, 'entries': external})
    result['initializerCount'] = len(initializers)
    result['initializerElementTypes'] = dict(initializer_types)
    result['operators'] = dict(sorted(operators.items()))
    for item in result['inputs']:
        item['directNodeUses'] = uses[item['name']]
        item['hasInitializerDefault'] = item['name'] in initializers
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('models', nargs='+', type=Path)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    reports = []
    for path in args.models:
        reports.append(inspect(path))
    args.output.write_text(json.dumps(reports, indent=2) + '\n')
    for report in reports:
        print(report['path'], len(report['inputs']), 'inputs;', len(report['outputs']), 'outputs;', report['initializerCount'], 'initializers')


if __name__ == '__main__':
    main()
