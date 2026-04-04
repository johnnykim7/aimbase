import { cn } from "@/lib/utils";

interface LoadingSpinnerProps {
  size?: number;
  fullPage?: boolean;
}

export const LoadingSpinner = ({ size = 24, fullPage }: LoadingSpinnerProps) => {
  const spinner = (
    <div
      className="rounded-full border-2 border-border border-t-primary animate-spin"
      style={{ width: size, height: size }}
    />
  );

  if (fullPage) {
    return (
      <div className={cn("flex items-center justify-center h-full min-h-[200px]")}>
        {spinner}
      </div>
    );
  }

  return spinner;
};
