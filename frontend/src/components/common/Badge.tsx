import { cn } from "@/lib/utils";

export type BadgeColor = "accent" | "success" | "warning" | "danger" | "purple" | "muted";

const colorClasses: Record<BadgeColor, { badge: string; dot: string }> = {
  accent:  { badge: "bg-primary/10 text-primary",          dot: "bg-primary" },
  success: { badge: "bg-success/10 text-success",          dot: "bg-success" },
  warning: { badge: "bg-warning/10 text-warning",          dot: "bg-warning" },
  danger:  { badge: "bg-destructive/10 text-destructive",  dot: "bg-destructive" },
  purple:  { badge: "bg-info/10 text-info",                dot: "bg-info" },
  muted:   { badge: "bg-muted text-muted-foreground",      dot: "bg-muted-foreground/50" },
};

interface BadgeProps {
  color?: BadgeColor;
  pulse?: boolean;
  children: React.ReactNode;
}

export const Badge = ({ color = "accent", pulse, children }: BadgeProps) => {
  const c = colorClasses[color];
  return (
    <span
      className={cn(
        "inline-flex items-center gap-1.5 px-2 py-0.5 rounded-md text-[11px] font-semibold tracking-wide whitespace-nowrap",
        c.badge
      )}
    >
      {pulse && (
        <span className={cn("size-1.5 rounded-full shrink-0 animate-pulse-dot", c.dot)} />
      )}
      {children}
    </span>
  );
};
