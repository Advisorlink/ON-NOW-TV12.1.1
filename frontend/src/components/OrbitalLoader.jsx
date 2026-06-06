import React from "react";

/**
 * OrbitalLoader — the brand loader for Vesper TV.
 *
 * Per v2.9.1 user request: a SIMPLE, slow, single-colour blue
 * spinning ring.  No purple, no pulses, no glow — just a quiet
 * 3⁄4-arc ring rotating at 2.4s/turn so the buffering state
 * doesn't fight for attention.
 *
 * Usage: <OrbitalLoader size={48} />
 */
export const OrbitalLoader = ({
  size = 48,
  color = "#5DC8FF",
  className = "",
}) => {
  const id = React.useId().replace(/:/g, "");
  const stroke = Math.max(2, Math.round(size * 0.07));
  const radius = size / 2 - stroke;
  const circumference = 2 * Math.PI * radius;
  // 3⁄4 of the ring is drawn, 1⁄4 is the gap that gives the spin
  // its sense of motion.
  const dash = circumference * 0.75;
  const gap = circumference * 0.25;

  return (
    <div
      className={`orbital-loader ${className}`}
      data-testid="orbital-loader"
      style={{
        width: size,
        height: size,
        display: "inline-block",
        lineHeight: 0,
      }}
    >
      <style>{`
        @keyframes orbital-spin-${id} {
          0%   { transform: rotate(0deg); }
          100% { transform: rotate(360deg); }
        }
        .orbital-loader-svg-${id} {
          animation: orbital-spin-${id} 2.4s linear infinite;
          transform-origin: 50% 50%;
        }
      `}</style>
      <svg
        className={`orbital-loader-svg-${id}`}
        width={size}
        height={size}
        viewBox={`0 0 ${size} ${size}`}
      >
        <circle
          cx={size / 2}
          cy={size / 2}
          r={radius}
          fill="none"
          stroke={color}
          strokeWidth={stroke}
          strokeLinecap="round"
          strokeDasharray={`${dash} ${gap}`}
        />
      </svg>
    </div>
  );
};

export default OrbitalLoader;
