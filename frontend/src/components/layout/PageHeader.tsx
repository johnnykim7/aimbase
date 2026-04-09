interface PageHeaderProps {
  title: string;
  subtitle?: string;
  actions?: React.ReactNode;
}

export const PageHeader = ({ title, subtitle, actions }: PageHeaderProps) => (
  <div className="flex items-center justify-between mb-7">
    <div>
      <h1 className="text-xl font-bold text-foreground">{title}</h1>
      {subtitle && (
        <p className="mt-1 text-xs text-muted-foreground">{subtitle}</p>
      )}
    </div>
    {actions && <div className="flex gap-2">{actions}</div>}
  </div>
);
