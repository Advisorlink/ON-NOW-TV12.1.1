import React from "react";

/**
 * OrbitalLoader — the brand loader for Vesper TV.
 *
 * Glassmorphism centre disk with two coloured dots orbiting in
 * opposite directions at slightly different speeds — feels alive
 * rather than mechanical.  Pure CSS animations, no JS, no images.
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
  const dotR = Math.max(4, Math.round(size * 0.045));
  const orbit = Math.round(size * 0.42);
  const disk = Math.round(size * 0.18);

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
        @keyframes orbital-spin-${id} {
          to { transform: rotate(360deg); }
        }
        @keyframes orbital-spin-rev-${id} {
          to { transform: rotate(-360deg); }
        }
      `}</style>

      {/* Glass centre */}
      <div
        style={{
          position: "absolute",
          left: "50%",
          top: "50%",
          width: disk * 2,
          height: disk * 2,
          marginLeft: -disk,
          marginTop: -disk,
          borderRadius: "50%",
          background: "rgba(255,255,255,0.06)",
          backdropFilter: "blur(8px)",
          border: "1px solid rgba(255,255,255,0.14)",
          boxShadow: "inset 4px 6px 12px rgba(255,255,255,0.08)",
        }}
      />

      {/* Outer orbit A (blue) */}
      <div
        style={{
          position: "absolute",
          inset: 0,
          animation: `orbital-spin-${id} 1.4s linear infinite`,
        }}
      >
        <div
          style={{
            position: "absolute",
            top: `calc(50% - ${orbit + dotR}px)`,
            left: `calc(50% - ${dotR}px)`,
            width: dotR * 2,
            height: dotR * 2,
            borderRadius: "50%",
            background: accentA,
            boxShadow: `0 0 ${dotR * 4}px ${accentA}, 0 0 ${dotR * 1.6}px ${accentA}`,
          }}
        />
      </div>

      {/* Outer orbit B (purple) — opposite direction, ~1.21× slower */}
      <div
        style={{
          position: "absolute",
          inset: 0,
          animation: `orbital-spin-rev-${id} 1.7s linear infinite`,
        }}
      >
        <div
          style={{
            position: "absolute",
            bottom: `calc(50% - ${orbit + dotR}px)`,
            left: `calc(50% - ${dotR}px)`,
            width: dotR * 2,
            height: dotR * 2,
            borderRadius: "50%",
            background: accentB,
            boxShadow: `0 0 ${dotR * 4}px ${accentB}, 0 0 ${dotR * 1.6}px ${accentB}`,
          }}
        />
      </div>
    </div>
  );
};

export default OrbitalLoader;
