// Unicode-safe encoder and decoder for media objects
export function safeEncodeMedia(media) {
  try {
    const jsonStr = JSON.stringify(media);
    // Unescape + encodeURIComponent converts Unicode code points into Latin1-safe escaped bytes for btoa
    return encodeURIComponent(btoa(unescape(encodeURIComponent(jsonStr))));
  } catch (e) {
    try {
      return encodeURIComponent(media.id || media.title || "media");
    } catch (_) {
      return "item";
    }
  }
}

export function safeDecodeMedia(encodedStr) {
  if (!encodedStr) return null;
  try {
    const decodedStr = decodeURIComponent(encodedStr);
    const latin1Str = atob(decodedStr);
    const jsonStr = decodeURIComponent(escape(latin1Str));
    return JSON.parse(jsonStr);
  } catch (e) {
    // If not base64 JSON, treat as raw id
    try {
      const rawId = decodeURIComponent(encodedStr);
      return { id: rawId, title: rawId, kind: "anime" };
    } catch (_) {
      return null;
    }
  }
}
