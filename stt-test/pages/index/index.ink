<script def>
{
  "navigationBarTitleText": "STT Test",
  "description": "Standalone SpeechRecognition proof-of-concept — no bridge, no network. Renders the live transcript on the glasses display and logs to logcat.",
  "schema": { "data": { "type": "object", "properties": {} } }
}
</script>

<script setup>
let recognition = null;
let restartTimer = null;
let startSeq = 0;

const AUTO_RESTART_DELAY_MS = 700;

function safeText(v) { return v == null ? '' : String(v); }

function readBest(event) {
  try {
    const best = event && event.results && event.results[0] && event.results[0][0];
    return best ? safeText(best.transcript) : '';
  } catch (_) { return ''; }
}

export default {
  data: {
    status: 'booting…',
    partial: '',
    finalText: '',
    supported: 'checking…',
    counter: 0
  },

  onLoad() {
    const hasGlobal = typeof SpeechRecognition !== 'undefined';
    this.setData({
      supported: hasGlobal ? 'SpeechRecognition: yes' : 'SpeechRecognition: NO',
      status: hasGlobal ? 'auto-starting…' : 'unsupported'
    });
    if (hasGlobal) this.scheduleRestart('onLoad', 300);
  },

  onShow() { this.scheduleRestart('onShow', 300); },

  scheduleRestart(reason, delay = AUTO_RESTART_DELAY_MS) {
    if (restartTimer) clearTimeout(restartTimer);
    restartTimer = setTimeout(() => {
      restartTimer = null;
      this.startAsr(reason);
    }, delay);
  },

  startAsr(reason = 'manual') {
    const seq = ++startSeq;
    if (typeof SpeechRecognition === 'undefined') return;
    try {
      recognition = new SpeechRecognition();
      recognition.lang = 'zh-CN';
      recognition.continuous = false;
      recognition.interimResults = true;
      recognition.maxAlternatives = 3;

      recognition.onstart = () => {
        if (seq !== startSeq) return;
        this.setData({ status: '● listening #' + seq });
        console.log('[stt-test] onstart #' + seq);
      };
      recognition.onresult = (event) => {
        if (seq !== startSeq) return;
        const text = readBest(event);
        let isFinal = false;
        try { isFinal = !!(event && event.results && event.results[0] && event.results[0].isFinal); } catch (_) {}
        if (isFinal) {
          const n = (this.data.counter || 0) + 1;
          this.setData({ finalText: text, partial: '', counter: n, status: '✓ final #' + n });
          console.log('[stt-test] FINAL: ' + text);
        } else {
          this.setData({ partial: text, status: '… partial' });
          console.log('[stt-test] partial: ' + text);
        }
      };
      recognition.onerror = (event) => {
        if (seq !== startSeq) return;
        const err = safeText(event && event.error);
        recognition = null;
        this.setData({ status: 'err ' + err + ' → restart' });
        console.log('[stt-test] onerror: ' + err);
        this.scheduleRestart('err-' + err, 900);
      };
      recognition.onend = () => {
        if (seq !== startSeq) return;
        recognition = null;
        this.setData({ status: 'end → restart' });
        this.scheduleRestart('end', AUTO_RESTART_DELAY_MS);
      };

      recognition.start();
    } catch (err) {
      this.setData({ status: 'start exception: ' + safeText(err && err.message || err) });
      console.log('[stt-test] start exception: ' + err);
      this.scheduleRestart('exception', 1500);
    }
  }
};
</script>

<page>
  <view class="root">
    <text class="status">{{status}}</text>
    <text class="supported">{{supported}}</text>
    <view class="divider"></view>
    <text class="label">最新完整识别 (final #{{counter}})</text>
    <text class="final">{{finalText}}</text>
    <text class="label">实时片段 (partial)</text>
    <text class="partial">{{partial}}</text>
  </view>
</page>

<style>
.root {
  background: #000;
  width: 480px;
  height: 640px;
  padding: 20px 16px;
  align-items: stretch;
}
.status {
  color: #40FF5E;
  font-size: 20px;
  line-height: 26px;
  margin-bottom: 4px;
}
.supported {
  color: #8040FF5E;
  font-size: 16px;
  line-height: 22px;
  margin-bottom: 12px;
}
.divider {
  height: 2px;
  background: #6640FF5E;
  margin: 4px 0;
}
.label {
  color: #8040FF5E;
  font-size: 16px;
  line-height: 22px;
  margin-top: 8px;
}
.final {
  color: #40FF5E;
  font-size: 24px;
  line-height: 32px;
  margin-top: 4px;
  min-height: 64px;
}
.partial {
  color: #8040FF5E;
  font-size: 20px;
  line-height: 26px;
  margin-top: 4px;
  min-height: 26px;
}
</style>
