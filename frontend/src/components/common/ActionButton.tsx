import { cn } from "@/lib/utils";

type ButtonVariant = "primary" | "danger" | "success" | "default" | "ghost";

interface ActionButtonProps {
  children?: React.ReactNode;
  variant?: ButtonVariant;
  onClick?: () => void;
  icon?: React.ReactNode;
  small?: boolean;
  disabled?: boolean;
  type?: "button" | "submit";
}

const variantClasses: Record<ButtonVariant, string> = {
  primary:
    "bg-foreground text-background shadow-xs shadow-black/20 hover:bg-foreground/85",
  danger:
    "bg-destructive text-destructive-foreground shadow-xs shadow-destructive/20 hover:bg-destructive/90",
  success:
    "bg-success text-success-foreground shadow-xs shadow-success/20 hover:bg-success/90",
  default:
    "bg-card text-foreground border border-border shadow-xs shadow-black/5 hover:bg-accent",
  ghost:
    "bg-transparent text-muted-foreground hover:bg-accent hover:text-foreground",
};

export const ActionButton = ({
  children,
  variant = "default",
  onClick,
  icon,
  small,
  disabled,
  type = "button",
}: ActionButtonProps) => (
  <button
    type={type}
    onClick={onClick}
    disabled={disabled}
    className={cn(
      "inline-flex items-center gap-1.5 rounded-lg font-medium whitespace-nowrap transition-all duration-150 cursor-pointer",
      "focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring/40 focus-visible:ring-offset-1",
      small ? "px-3 py-1.5 text-xs" : "px-4 py-2 text-[13px]",
      variantClasses[variant],
      disabled && "opacity-50 cursor-not-allowed pointer-events-none"
    )}
  >
    {icon && <span className={cn("shrink-0", small ? "text-[13px]" : "text-[15px]")}>{icon}</span>}
    {children}
  </button>
);
