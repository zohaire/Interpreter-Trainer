(() => {
  if (window.__interpreterLiveNativeDuplexV2) return 'ready';
  if (!window.InterpreterNative || !window.InterpreterLiveNative) return 'pending';

  // Do not replace window.InterpreterNative. addJavascriptInterface exposes a special WebView
  // object whose replacement semantics vary across Android WebView versions. The fast-voice and
  // interruption layers call InterpreterLiveNative directly for live speech/VAD and keep
  // InterpreterNative exclusively for recognition/control.
  window.__interpreterLiveNativeDuplexV2 = true;
  window.__interpreterLiveNativeDuplexV1 = true;
  return 'ready';
})();
