const url = "https://fsharetv.cc/api/media/5554501c31520f1c30572f110b503d141e490b121652235d263b0c562a231c0c52245551545251565d525d55?hash=104xU6kxT3";
const isDirectStreamUrl = (url) => {
  const clean = String(url || "").split("#")[0];
  return /\.(m3u8|mp4|mpd|webm)(\?|$)/i.test(clean) ||
    /\/(?:playlist|manifest|hls|dash)(?:[/?#]|$)/i.test(clean);
};
console.log("isDirectStreamUrl:", isDirectStreamUrl(url));
