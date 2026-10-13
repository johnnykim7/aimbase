import { useMemo, useState } from "react";
import { ChevronDown, ChevronRight, FileText, File as FileIcon, Folder } from "lucide-react";
import { Button } from "../ui/button";
import type { SkillBundleFileEntry } from "../../types/skillBundle";
import { formatBytes } from "./skillBundleText";

interface TreeNode {
  name: string;
  path: string;
  file?: SkillBundleFileEntry;
  children: TreeNode[];
}

function buildTree(files: SkillBundleFileEntry[]): TreeNode[] {
  const root: TreeNode = { name: "", path: "", children: [] };
  for (const f of files) {
    const parts = f.path.split("/");
    let node = root;
    parts.forEach((part, i) => {
      const path = parts.slice(0, i + 1).join("/");
      let child = node.children.find((c) => c.path === path);
      if (!child) {
        child = { name: part, path, children: [] };
        node.children.push(child);
      }
      if (i === parts.length - 1) child.file = f;
      node = child;
    });
  }
  const sort = (nodes: TreeNode[]) => {
    // 폴더를 먼저, 그 안에서는 이름 순
    nodes.sort((a, b) => Number(!!a.file) - Number(!!b.file) || a.name.localeCompare(b.name));
    nodes.forEach((n) => sort(n.children));
  };
  sort(root.children);
  return root.children;
}

interface SkillFileTreeProps {
  files: SkillBundleFileEntry[];
  selectedPath?: string;
  onSelect: (file: SkillBundleFileEntry) => void;
}

export function SkillFileTree({ files, selectedPath, onSelect }: SkillFileTreeProps) {
  const tree = useMemo(() => buildTree(files), [files]);
  const [open, setOpen] = useState<Set<string>>(new Set());

  const toggle = (path: string) =>
    setOpen((prev) => {
      const next = new Set(prev);
      if (next.has(path)) next.delete(path);
      else next.add(path);
      return next;
    });

  const renderNode = (node: TreeNode, depth: number): React.ReactNode => {
    const indent = { paddingLeft: 8 + depth * 14 };
    if (!node.file) {
      const expanded = open.has(node.path);
      return (
        <div key={node.path}>
          <Button
            variant="ghost"
            size="sm"
            className="w-full justify-start font-normal"
            style={indent}
            onClick={() => toggle(node.path)}
          >
            {expanded ? <ChevronDown /> : <ChevronRight />}
            <Folder />
            <span className="truncate">{node.name}</span>
          </Button>
          {expanded && node.children.map((c) => renderNode(c, depth + 1))}
        </div>
      );
    }
    const f = node.file;
    return (
      <Button
        key={node.path}
        variant="ghost"
        size="sm"
        selected={selectedPath === f.path}
        className="w-full justify-start font-normal"
        style={{ paddingLeft: 8 + depth * 14 + 18 }}
        onClick={() => onSelect(f)}
      >
        {f.is_text ? <FileText /> : <FileIcon />}
        <span className="truncate font-mono">{node.name}</span>
        <span className="ml-auto text-muted-foreground">{formatBytes(f.size_bytes)}</span>
      </Button>
    );
  };

  return <div className="flex flex-col">{tree.map((n) => renderNode(n, 0))}</div>;
}
