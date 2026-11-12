import React, { useState, useRef, useEffect } from "react";
import confetti from "canvas-confetti";
import { sound } from "../utils/audio";

export default function LockScreen({ onUnlock }) {
  const [password, setPassword] = useState("");
  const [showPassword, setShowPassword] = useState(false);
  const [error, setError] = useState(false);
  const [shake, setShake] = useState(false);
  const inputRef = useRef(null);

  useEffect(() => {
    // Auto-focus password input
    const timer = setTimeout(() => {
      inputRef.current?.focus();
    }, 400);
    return () => clearTimeout(timer);
  }, []);

  const handleUnlock = () => {
    const clean = password.trim().toLowerCase();
    // Accept "Micheal and IJ", with common variations (case-insensitive, ampersand, spelling)
    const valid = [
      "micheal and ij",
      "michael and ij",
      "micheal & ij",
      "michael & ij"
    ];

    if (valid.includes(clean)) {
      setError(false);
      try {
        sound.win();
      } catch (_) {}
      try {
        confetti({
          particleCount: 100,
          spread: 80,
          origin: { y: 0.5 },
          colors: ["#ff4081", "#ffd700", "#ff80ab", "#ffffff", "#e0246a"]
        });
      } catch (_) {}
      
      try {
        sessionStorage.setItem("ij_unlocked", "true");
      } catch (_) {}

      onUnlock();
    } else {
      try {
        sound.tap(220);
      } catch (_) {}
      setError(true);
      setShake(true);
      setTimeout(() => setShake(false), 550);
    }
  };

  const handleKeyDown = (e) => {
    if (e.key === "Enter") {
      handleUnlock();
    }
  };

  return (
    <div className="lock-screen-root">
      {/* Ambient dark glow background */}
      <div className="lock-ambient-bg" />

      <div className={`lock-card ${shake ? "shake" : ""}`}>
        {/* Animated padlock icon */}
        <div className="lock-icon-wrap">
          <div className="lock-icon">
            <span>🔐</span>
          </div>
        </div>

        {/* Headings */}
        <h1 className="lock-title">Private Sanctuary</h1>
        <p className="lock-subtitle">
          for <em>Ijeoma</em> · protected by our secret key
        </p>

        {/* Password Input Box */}
        <div className="lock-input-group">
          <div className="lock-input-wrapper">
            <span className="lock-input-icon">🗝️</span>
            <input
              ref={inputRef}
              type={showPassword ? "text" : "password"}
              className={`lock-input ${error ? "has-error" : ""}`}
              placeholder="Enter password..."
              value={password}
              onChange={(e) => {
                setPassword(e.target.value);
                if (error) setError(false);
              }}
              onKeyDown={handleKeyDown}
              autoComplete="off"
              autoCorrect="off"
              autoCapitalize="off"
              spellCheck="false"
            />
            <button
              type="button"
              className="lock-toggle-visibility"
              onClick={() => setShowPassword(!showPassword)}
              aria-label={showPassword ? "Hide password" : "Show password"}
            >
              {showPassword ? "🙈" : "👁️"}
            </button>
          </div>

          {error && (
            <p className="lock-error-msg">
              Incorrect password · please try again 🖤
            </p>
          )}
        </div>

        {/* Unlock Button */}
        <button
          type="button"
          className="lock-submit-btn"
          onClick={handleUnlock}
        >
          <span>Unlock Our World</span>
          <span className="lock-btn-arrow">→</span>
        </button>

        <footer className="lock-footer">
          <span>built with endless devotion · for her eyes only</span>
        </footer>
      </div>
    </div>
  );
}
