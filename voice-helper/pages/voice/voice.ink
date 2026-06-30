<script def>
export default { name: "voice" };
</script>

<script setup>
let ws = null;
let rec = null;
function start() {
  ws = new WebSocket("ws://127.0.0.1:48761");
  ws.onopen = () => {
    ws.send(JSON.stringify({type: "ready"}));
    rec = new SpeechRecognition();
    rec.lang = "zh-CN";
    rec.interimResults = true;
    rec.continuous = false;
    rec.onresult = (e) => {
      const r = e.results[e.results.length - 1];
      const text = r[0].transcript;
      ws.send(JSON.stringify({type: r.isFinal ? "final" : "interim", text}));
      if (r.isFinal) { rec.stop(); }
    };
    rec.onerror = (e) => {
      ws.send(JSON.stringify({type: "error", code: e.error || "unknown", msg: ""}));
      close();
    };
    rec.onend = () => close();
    rec.start();
  };
  ws.onerror = () => close();
}
function close() {
  try { rec && rec.stop(); } catch(_) {}
  try { ws && ws.close(); } catch(_) {}
  setTimeout(() => { try { App.exit && App.exit(); } catch(_){} }, 100);
}
onLoad(start);
</script>

<page>
  <view class="root">
    <text class="hint">聆听中…</text>
  </view>
</page>

<style>
.root { background: #000; width: 480px; height: 640px; align-items: center; justify-content: center; }
.hint { color: #40FF5E; font-size: 24px; }
</style>
