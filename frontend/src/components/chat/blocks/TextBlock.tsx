interface TextBlockProps {
  text: string;
}

export const TextBlock = ({ text }: TextBlockProps) => (
  <div className="whitespace-pre-wrap text-sm">{text}</div>
);
