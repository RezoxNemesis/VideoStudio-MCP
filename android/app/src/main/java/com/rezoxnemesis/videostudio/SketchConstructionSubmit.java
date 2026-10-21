package com.rezoxnemesis.videostudio;

import java.util.function.Consumer;
import org.json.JSONObject;

/** Completes with null after persistence succeeds, or the failure that permits a retry. */
@FunctionalInterface
interface SketchConstructionSubmit {
    void submit(JSONObject request,Consumer<Exception> completion);

    /** Compatibility for synchronous callers; asynchronous stores use submit directly. */
    static SketchConstructionSubmit from(Consumer<JSONObject> create){
        return (request,completion)->{
            try{create.accept(request);}catch(Exception error){completion.accept(error);return;}
            completion.accept(null);
        };
    }
}
