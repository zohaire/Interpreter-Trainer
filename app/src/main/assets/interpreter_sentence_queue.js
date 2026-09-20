(() => {
  // One speech queue per reply. Completion means both text and audio have ended.
  window.createInterpreterSpeechQueue = ({speak, stop, onDone}) => {
    let pending = '', queue = [], speaking = false, complete = false, cancelled = false, delivered = false;
    const pump = () => {
      if (cancelled || speaking) return;
      if (queue.length) {
        speaking = true;
        const text = queue.shift();
        if (!speak(text)) { speaking = false; pump(); }
      } else if (complete && !delivered) { delivered = true; onDone(); }
    };
    return {
      append(text) {
        if (cancelled || complete) return;
        pending += text;
        let boundary;
        while ((boundary = pending.search(/[.!?؟。！](?:\s|$)/)) >= 0) {
          const sentence = pending.slice(0, boundary + 1).trim();
          pending = pending.slice(boundary + 1);
          if (sentence) queue.push(sentence.replace(/[*#`]/g, ''));
        }
        // Long clauses must not delay audio indefinitely; split at a word boundary.
        if (pending.length > 240) {
          const end = pending.lastIndexOf(' ', 220);
          if (end > 0) { queue.push(pending.slice(0,end)); pending = pending.slice(end); }
        }
        pump();
      },
      finish() { if (cancelled) return; if (pending.trim()) queue.push(pending.trim().replace(/[*#`]/g,'')); pending=''; complete=true; pump(); },
      speechEnded() { if (cancelled) return; speaking=false; pump(); },
      cancel() { cancelled=true; queue=[]; pending=''; stop(); },
      get active() { return !cancelled && !delivered; }
    };
  };
})();
