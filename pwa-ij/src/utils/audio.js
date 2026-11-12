// Web Audio soft synth (pink-leaning chimes & taps) — pure Web Audio API, no external assets needed.
class PinkSynth {
  constructor() {
    this.ctx = null;
    this.enabled = true;
  }

  init() {
    if (!this.ctx && typeof window !== "undefined") {
      const AC = window.AudioContext || window.webkitAudioContext;
      if (AC) this.ctx = new AC();
    }
    if (this.ctx && this.ctx.state === "suspended") {
      this.ctx.resume();
    }
  }

  tone(freq, dur, type = "sine", vol = 0.12, ramp = null) {
    if (!this.enabled) return;
    try {
      this.init();
      if (!this.ctx) return;
      const osc = this.ctx.createOscillator();
      const gain = this.ctx.createGain();
      const t = this.ctx.currentTime;
      osc.type = type;
      osc.frequency.setValueAtTime(freq, t);
      if (ramp) osc.frequency.exponentialRampToValueAtTime(ramp, t + dur);
      gain.gain.setValueAtTime(vol, t);
      gain.gain.exponentialRampToValueAtTime(0.001, t + dur);
      osc.connect(gain);
      gain.connect(this.ctx.destination);
      osc.start(t);
      osc.stop(t + dur);
    } catch {
      /* audio policy safe */
    }
  }

  tap(freq = 523) {
    this.tone(freq, 0.09, "sine", 0.1, freq * 1.4);
  }

  win() {
    [523.25, 659.25, 783.99, 1046.5].forEach((f, i) => {
      setTimeout(() => this.tone(f, 0.3, "triangle", 0.14), i * 90);
    });
  }
}

export const sound = new PinkSynth();
