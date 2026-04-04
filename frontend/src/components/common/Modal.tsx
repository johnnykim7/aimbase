interface ModalProps {
  open: boolean;
  onClose: () => void;
  title: string;
  width?: number;
  children: React.ReactNode;
}

export const Modal = ({ open, onClose, title, width = 520, children }: ModalProps) => {
  if (!open) return null;

  return (
    <div
      className="fixed inset-0 z-[1000] flex items-center justify-center bg-black/50 backdrop-blur-[2px]"
      onClick={onClose}
    >
      <div
        className="bg-card border border-border rounded-xl shadow-lg p-7 max-w-[90vw] max-h-[85vh] overflow-y-auto"
        style={{ width }}
        onClick={(e) => e.stopPropagation()}
      >
        <h2 className="text-base font-semibold text-foreground mb-5">{title}</h2>
        {children}
      </div>
    </div>
  );
};
