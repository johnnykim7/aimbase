interface EmptyStateProps {
  icon?: React.ReactNode;
  title: string;
  description?: string;
  action?: React.ReactNode;
}

export const EmptyState = ({ icon, title, description, action }: EmptyStateProps) => (
  <div className="flex flex-col items-center justify-center py-20 px-10 text-center gap-2.5">
    {icon && (
      <div className="size-14 rounded-2xl bg-muted/80 border border-border flex items-center justify-center text-muted-foreground mb-2">
        {icon}
      </div>
    )}
    <div className="text-sm font-semibold text-foreground">{title}</div>
    {description && (
      <div className="text-xs text-muted-foreground max-w-[360px] leading-relaxed">
        {description}
      </div>
    )}
    {action && <div className="mt-3">{action}</div>}
  </div>
);
