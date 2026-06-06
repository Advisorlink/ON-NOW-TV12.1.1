import React from "react";

/**
 * OrbitalLoader — the brand loader for Vesper TV.
 *
 * Three concentric rings pulse outward from a glowing centre,
 * each ring staggered by ~1/3 of the cycle so the loop looks
 * continuous rather than metronomic.  Pure CSS animations, no
 * JS, no images.  Slowed to a 2.4s cycle so it feels graceful
 * on the loading screen, not anxious.
 *
 * Usage:
 *   <OrbitalLoader size={120} />
 *   <OrbitalLoader size={64} accentA="#5DC8FF" accentB="#FF8AA9" />
 */
export const OrbitalLoader = ({
  size = 120,
  accentA = "#5DC8FF",
  accentB = "#C16BFF",
  className = "",
}) => {
  // Each rendered loader gets a private animation namespace so two
  // instances on the same page don't share keyframe identifiers.
  const id = React.useId().replace(/:/g, "");
  const core = Math.max(6, Math.round(size * 0.11));
  const ring = Math.max(1.5, Math.round(size * 0.018));
  const cycle = 2.4; // seconds — slow + graceful

  const ringStyle = (delay, color) => ({
    position: "absolute",
    inset: 0,
    borderRadius: "50%",
    border: `${ring}px solid ${color}`,
    boxShadow: `0 0 ${ring * 6}px ${color}`,
    opacity: 0,
    transformOrigin: "center center",
    animation: `orbital-pulse-${id} ${cycle}s cubic-bezier(0.22, 0.61, 0.36, 1) infinite`,
    animationDelay: `${delay}s`,
  });

  return (
    <div
      className={`orbital-loader ${className}`}
      data-testid="orbital-loader"
      style={{
        width: size,
        height: size,
        position: "relative",
        display: "inline-block",
      }}
    >
      <style>{`
        @keyframes orbital-pulse-${id} {
          0%   { transform: scale(0.18); opacity: 0.0; }
          15%  { opacity: 0.95; }
          100% { transform: scale(1.0);  opacity: 0.0; }
        }
        @keyframes orbital-core-${id} {
          0%, 100% { transform: scale(1.0); opacity: 0.85; }
          50%      { transform: scale(1.18); opacity: 1.0; }
        }
      `}</style>

      {/* Three pulsating rings — staggered by 1/3 of the cycle so
          there's always a ring expanding outward. */}
      <div style={ringStyle(0, accentA)} />
      <div style={ringStyle(cycle / 3, accentB)} />
      <div style={ringStyle((cycle * 2) / 3, accentA)} />

      {/* Glowing centre core — gently breathes with the ring rhythm. */}
      <div
        style={{
          position: "absolute",
          left: "50%",
          top: "50%",
          width: core * 2,
          height: core * 2,
          marginLeft: -core,
          marginTop: -core,
          borderRadius: "50%",
          background: `radial-gradient(circle at 35% 35%, ${accentA}, ${accentB})`,
          boxShadow: `0 0 ${core * 1.8}px ${accentA}, 0 0 ${core * 0.9}px ${accentB}`,
          animation: `orbital-core-${id} ${cycle}s ease-in-out infinite`,
        }}
      />
    </div>
  );
};

export default OrbitalLoader;
