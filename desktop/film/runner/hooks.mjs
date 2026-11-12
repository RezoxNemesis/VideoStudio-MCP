// A film's own files are loaded again whenever they change: the version that film.js was imported with is handed down
// to every file it imports by a relative path (its sequences, its helpers). The kit is reached by an absolute address
// and stays loaded once.
export async function resolve(specifier, context, nextResolve) {
  const r = await nextResolve(specifier, context);
  const m = context.parentURL && /[?&]v=([\w.]+)/.exec(context.parentURL);
  if (m && (specifier.startsWith('./') || specifier.startsWith('../')) && r.url.startsWith('file:') && !r.url.includes('?')) return { ...r, url: `${r.url}?v=${m[1]}` };
  return r;
}
