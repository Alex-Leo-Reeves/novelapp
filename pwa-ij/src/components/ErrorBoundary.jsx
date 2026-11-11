import React from "react";

export default class ErrorBoundary extends React.Component {
  constructor(props) {
    super(props);
    this.state = { hasError: false, error: null };
  }

  static getDerivedStateFromError(error) {
    return { hasError: true, error };
  }

  componentDidCatch(error, errorInfo) {
    console.error("[IJ PWA] Component Error Boundary caught:", error, errorInfo);
  }

  handleReload = () => {
    this.setState({ hasError: false, error: null });
    window.location.reload();
  };

  render() {
    if (this.state.hasError) {
      return (
        <div
          style={{
            padding: 24,
            textAlign: "center",
            display: "flex",
            flexDirection: "column",
            alignItems: "center",
            justifyContent: "center",
            height: "100dvh",
            background: "#fff5f8",
            fontFamily: "'Plus Jakarta Sans', sans-serif",
          }}
        >
          <div style={{ fontSize: "3rem", marginBottom: 12 }}>💖</div>
          <h2 style={{ color: "#ff2d78", marginBottom: 8, fontFamily: "'Playfair Display', serif" }}>
            A gift for IJ ❤️
          </h2>
          <p style={{ color: "#70264b", fontSize: "0.95rem", maxWidth: 300, marginBottom: 20 }}>
            Something hiccuped while loading. Tap below to reload.
          </p>
          <button
            onClick={this.handleReload}
            style={{
              background: "linear-gradient(135deg, #ff2d78, #ff7bb0)",
              color: "#fff",
              border: "none",
              borderRadius: "9999px",
              padding: "10px 24px",
              fontWeight: 700,
              cursor: "pointer",
              boxShadow: "0 4px 12px rgba(255, 45, 120, 0.3)",
            }}
          >
            Reload Page
          </button>
        </div>
      );
    }
    return this.props.children;
  }
}
