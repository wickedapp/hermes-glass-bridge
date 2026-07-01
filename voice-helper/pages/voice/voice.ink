<script def>
{
  "navigationBarTitleText": "TG Voice Helper",
  "description": "Rokid SpeechRecognition helper for Telegram native app.",
  "schema": { "data": { "type": "object", "properties": {} } }
}
</script>

<script setup>
let recognition = null;
let ws = null;
let startSeq = 0;
let lastPartial = '';
let finalSent = false;

const BRIDGE_HOSTS = ['192.168.68.67'];
let bridgeHostIndex = 0;
function bridgeUrl() { return 'ws://' + BRIDGE_HOSTS[bridgeHostIndex] + ':48761'; }

function safeText(v) { return v == null ? '' : String(v); }
function readBest(event) {
  try {
    const best = event && event.results && event.results[0] && event.results[0][0];
    return best ? safeText(best.transcript) : '';
  } catch (_) { return ''; }
}
function isFinalResult(event) {
  try { return !!(event && event.results && event.results[0] && event.results[0].isFinal); } catch (_) { return false; }
}
function sendFrame(frame) {
  try { if (ws) ws.send(JSON.stringify(frame)); } catch (err) { console.log('[tg-voice] send err ' + err); }
}
function sendFinalText(text) {
  const finalText = safeText(text || lastPartial).trim();
  if (finalSent || finalText.length === 0) return;
  finalSent = true;
  sendFrame({ type: 'final', text: finalText });
  console.log('[tg-voice] FINAL: ' + finalText);
}
function closeAll() {
  try { recognition && recognition.stop(); } catch(_) {}
  try { ws && ws.close(); } catch(_) {}
  recognition = null;
  ws = null;
}

export default {
  data: {
    status: 'booting…',
    supported: 'checking…',
    partial: '',
    finalText: '',
    bridge: '語音服務準備中'
  },

  onLoad() {
    const supported = typeof SpeechRecognition !== 'undefined';
    this.setData({ supported: supported ? 'SpeechRecognition: yes' : 'SpeechRecognition: NO' });
    if (supported) this.connectBridge();
    else this.setData({ status: 'unsupported' });
  },

  onUnload() { closeAll(); },

  connectBridge() {
    const url = bridgeUrl();
    this.setData({ status: '正在啟動語音…', bridge: '請稍候' });
    console.log('[tg-voice] connect ' + url);
    try {
      ws = new WebSocket(url);
      let opened = false;
      ws.onopen = () => {
        opened = true;
        this.setData({ status: '● listening', bridge: 'Rokid 內建語音識別' });
        console.log('[tg-voice] ws open ' + url);
        sendFrame({ type: 'ready', nonce: '' });
        this.startAsr();
      };
      ws.onerror = () => {
        console.log('[tg-voice] ws error ' + url);
        if (!opened && bridgeHostIndex + 1 < BRIDGE_HOSTS.length) {
          bridgeHostIndex++;
          setTimeout(() => this.connectBridge(), 250);
        } else {
          this.setData({ status: '● listening', bridge: 'Rokid 內建語音識別' });
          this.startAsr();
        }
      };
      ws.onclose = () => { console.log('[tg-voice] ws close ' + url); };
    } catch (err) {
      console.log('[tg-voice] ws exception ' + safeText(err && err.message || err));
      this.setData({ status: 'ws exception: ' + safeText(err && err.message || err) });
      this.startAsr();
    }
  },

  startAsr() {
    const seq = ++startSeq;
    lastPartial = '';
    finalSent = false;
    try {
      recognition = new SpeechRecognition();
      recognition.lang = 'zh-CN';
      recognition.interimResults = true;
      recognition.continuous = false;
      recognition.maxAlternatives = 3;
      recognition.onstart = () => {
        if (seq !== startSeq) return;
        this.setData({ status: '● listening' });
        console.log('[tg-voice] onstart');
        setTimeout(() => { try { if (recognition && !finalSent) recognition.stop(); } catch(_) {} }, 9000);
      };
      recognition.onresult = (event) => {
        if (seq !== startSeq) return;
        const text = readBest(event);
        if (text && text.length > 0) lastPartial = text;
        if (isFinalResult(event)) {
          this.setData({ finalText: text, partial: '', status: '✓ final' });
          sendFinalText(text);
          setTimeout(() => closeAll(), 200);
        } else {
          this.setData({ partial: text, status: '… partial' });
          console.log('[tg-voice] partial: ' + text);
          sendFrame({ type: 'interim', text });
        }
      };
      recognition.onerror = (event) => {
        if (seq !== startSeq) return;
        const err = safeText(event && event.error);
        this.setData({ status: 'err ' + err });
        console.log('[tg-voice] onerror: ' + err);
        sendFrame({ type: 'error', code: err, msg: '' });
      };
      recognition.onend = () => {
        if (seq !== startSeq) return;
        if (!finalSent && lastPartial.length > 0) {
          this.setData({ finalText: lastPartial, partial: '', status: '✓ final' });
          sendFinalText(lastPartial);
          setTimeout(() => closeAll(), 200);
        } else {
          this.setData({ status: 'ended' });
        }
      };
      recognition.start();
    } catch (err) {
      this.setData({ status: 'start exception: ' + safeText(err && err.message || err) });
      sendFrame({ type: 'error', code: 'start_exception', msg: safeText(err && err.message || err) });
    }
  }
};
</script>

<page>
  <view class="root">
    <text class="status">語音輸入</text>
    <text class="supported">{{status}}</text>
    <text class="bridge">{{bridge}}</text>
    <view class="divider"></view>
    <text class="label">实时片段</text>
    <text class="partial">{{partial}}</text>
    <text class="label">完整识别</text>
    <text class="final">{{finalText}}</text>
  </view>
</page>

<style>
.root { background: #000; width: 480px; height: 640px; padding: 20px 16px; align-items: stretch; }
.status { color: #40FF5E; font-size: 22px; line-height: 30px; }
.supported, .bridge, .label { color: #8040FF5E; font-size: 16px; line-height: 22px; margin-top: 4px; }
.divider { height: 2px; background: #6640FF5E; margin: 12px 0; }
.partial { color: #8040FF5E; font-size: 22px; line-height: 30px; min-height: 40px; }
.final { color: #40FF5E; font-size: 26px; line-height: 34px; min-height: 80px; }
</style>
