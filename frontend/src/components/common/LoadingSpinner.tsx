import { COLORS } from "../../theme";

interface LoadingSpinnerProps {
  size?: number;
  fullPage?: boolean;
}

export const LoadingSpinner = ({ size = 24, fullPage }: LoadingSpinnerProps) => {
  const spinner = (
    <div
      style={{
        width: size,
        height: size,
        borderRadius: "50%",
        border: `2px solid ${COLORS.border}`,
        borderTopColor: COLORS.accent,
        animation: "spin 0.8s linear infinite",
      }}
    />
  );

  if (fullPage) {
    return (
      <div
        style={{
          display: "flex",
          alignItems: "center",
          justifyContent: "center",
          height: "100%",
          minHeight: 200,
        }}
      >
        {spinner}
      </div>
    );
  }

  return spinner;
};
