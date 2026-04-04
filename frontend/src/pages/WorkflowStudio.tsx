import { useState, useCallback, useRef, useEffect, useMemo } from "react";
import { useParams, useNavigate } from "react-router-dom";
import {
  ReactFlow,
  addEdge,
  useNodesState,
  useEdgesState,
  Controls,
  MiniMap,
  Background,
  BackgroundVariant,
  type Connection,
  type OnConnect,
  type Node,
  type Edge,
  ReactFlowProvider,
  useReactFlow,
} from "@xyflow/react";
import "@xyflow/react/dist/style.css";

import { NodePalette } from "../components/workflow/NodePalette";
import { ConfigPanel } from "../components/workflow/ConfigPanel";
import { StudioToolbar } from "../components/workflow/StudioToolbar";
import WorkflowNode from "../components/workflow/nodes/WorkflowNode";
import { flowToWorkflow, hasCycle } from "../components/workflow/utils/flowToWorkflow";
import { workflowToFlow, autoLayout } from "../components/workflow/utils/workflowToFlow";
import { useWorkflows, useCreateWorkflow } from "../hooks/useWorkflows";
import { useUpdateWorkflow, useRunWorkflow } from "../hooks/useWorkflows";

const nodeTypes = { workflowNode: WorkflowNode };

let idCounter = 0;
function nextId() {
  return `node_${Date.now()}_${idCounter++}`;
}

function StudioInner({ embedded }: { embedded?: boolean }) {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const isNew = !id;
  const reactFlowWrapper = useRef<HTMLDivElement>(null);
  const { screenToFlowPosition } = useReactFlow();

  const { data: workflows = [] } = useWorkflows();
  const createWorkflow = useCreateWorkflow();
  const updateWorkflow = useUpdateWorkflow();
  const runWorkflow = useRunWorkflow();

  const existingWorkflow = useMemo(
    () => (id ? workflows.find((w) => w.id === id) : undefined),
    [id, workflows]
  );

  const [nodes, setNodes, onNodesChange] = useNodesState<Node>([] as Node[]);
  const [edges, setEdges, onEdgesChange] = useEdgesState<Edge>([] as Edge[]);
  const [name, setName] = useState("새 워크플로우");
  const [description, setDescription] = useState("");
  const [inputSchema, setInputSchema] = useState<string>("");
  const [outputSchema, setOutputSchema] = useState<string>("");
  const [selectedNode, setSelectedNode] = useState<Node | null>(null);
  const [dirty, setDirty] = useState(false);
  const [loaded, setLoaded] = useState(false);
  const [showSchemaPanel, setShowSchemaPanel] = useState(false);

  // 기존 워크플로우 로드
  useEffect(() => {
    if (existingWorkflow && !loaded) {
      const { nodes: n, edges: e } = workflowToFlow(existingWorkflow);
      setNodes(n);
      setEdges(e);
      setName(existingWorkflow.name);
      setDescription(existingWorkflow.description ?? "");
      if (existingWorkflow.inputSchema) {
        setInputSchema(JSON.stringify(existingWorkflow.inputSchema, null, 2));
      }
      if (existingWorkflow.outputSchema) {
        setOutputSchema(JSON.stringify(existingWorkflow.outputSchema, null, 2));
      }
      setLoaded(true);
    }
    if (isNew && !loaded) {
      setLoaded(true);
    }
  }, [existingWorkflow, isNew, loaded]);

  const onConnect: OnConnect = useCallback(
    (conn: Connection) => {
      setEdges((eds) => addEdge(conn, eds));
      setDirty(true);
    },
    [setEdges]
  );

  // 팔레트에서 드롭
  const onDragOver = useCallback((e: React.DragEvent) => {
    e.preventDefault();
    e.dataTransfer.dropEffect = "move";
  }, []);

  const onDrop = useCallback(
    (e: React.DragEvent) => {
      e.preventDefault();
      const type = e.dataTransfer.getData("application/workflow-node-type");
      const label = e.dataTransfer.getData("application/workflow-node-label");
      if (!type) return;

      const position = screenToFlowPosition({ x: e.clientX, y: e.clientY });

      // SUB_WORKFLOW 노드: 공용 워크플로우 ID를 config에 자동 설정
      const config: Record<string, unknown> = {};
      if (type === "sub_workflow") {
        const subId = e.dataTransfer.getData("application/workflow-sub-id");
        if (subId) {
          config.workflow_id = subId;
        }
      }

      const newNode: Node = {
        id: nextId(),
        type: "workflowNode",
        position,
        data: { label: label || type, type, config },
      };

      setNodes((nds) => [...nds, newNode]);
      setDirty(true);
    },
    [screenToFlowPosition, setNodes]
  );

  const onNodeClick = useCallback(
    (_: React.MouseEvent, node: Node) => {
      setSelectedNode(node);
    },
    []
  );

  const onPaneClick = useCallback(() => {
    setSelectedNode(null);
  }, []);

  const handleNodeUpdate = useCallback(
    (nodeId: string, data: Record<string, unknown>) => {
      setNodes((nds) =>
        nds.map((n) => (n.id === nodeId ? { ...n, data } : n))
      );
      setDirty(true);
    },
    [setNodes]
  );

  const handleNodeDelete = useCallback(
    (nodeId: string) => {
      setNodes((nds) => nds.filter((n) => n.id !== nodeId));
      setEdges((eds) => eds.filter((e) => e.source !== nodeId && e.target !== nodeId));
      setSelectedNode(null);
      setDirty(true);
    },
    [setNodes, setEdges]
  );

  const handleAutoLayout = useCallback(() => {
    const { nodes: laid, edges: laidE } = autoLayout(nodes, edges);
    setNodes(laid);
    setEdges(laidE);
  }, [nodes, edges, setNodes, setEdges]);

  const handleSave = useCallback(async () => {
    if (hasCycle(nodes, edges)) {
      alert("순환 의존이 감지되었습니다. DAG에는 순환이 허용되지 않습니다.");
      return;
    }

    const workflowId = id ?? `wf_${Date.now()}`;
    let parsedInputSchema: Record<string, unknown> | undefined;
    if (inputSchema.trim()) {
      try {
        parsedInputSchema = JSON.parse(inputSchema);
      } catch {
        alert("입력 스키마가 올바른 JSON이 아닙니다.");
        return;
      }
    }
    let parsedSchema: Record<string, unknown> | undefined;
    if (outputSchema.trim()) {
      try {
        parsedSchema = JSON.parse(outputSchema);
      } catch {
        alert("출력 스키마가 올바른 JSON이 아닙니다.");
        return;
      }
    }
    const req = flowToWorkflow(nodes, edges, {
      id: workflowId,
      name,
      description,
      status: "draft",
      inputSchema: parsedInputSchema,
      outputSchema: parsedSchema,
    });

    try {
      if (isNew) {
        await createWorkflow.mutateAsync(req);
        navigate(`/workflows/${workflowId}/edit`, { replace: true });
      } else {
        await updateWorkflow.mutateAsync({ id: workflowId, data: req });
      }
      setDirty(false);
    } catch (err) {
      console.error("저장 실패:", err);
      alert("저장에 실패했습니다.");
    }
  }, [nodes, edges, id, name, description, isNew, createWorkflow, updateWorkflow, navigate]);

  const handleRun = useCallback(async () => {
    if (!id) {
      alert("워크플로우를 먼저 저장해주세요.");
      return;
    }
    try {
      await runWorkflow.mutateAsync({ id });
    } catch (err) {
      console.error("실행 실패:", err);
    }
  }, [id, runWorkflow]);

  // 노드/엣지 변경 시 dirty 표시
  const handleNodesChange: typeof onNodesChange = useCallback(
    (changes) => {
      onNodesChange(changes);
      const hasMoved = changes.some((c) => c.type === "position" && c.dragging);
      if (hasMoved) setDirty(true);
    },
    [onNodesChange]
  );

  return (
    <div className="flex flex-col h-full bg-background">
      <StudioToolbar
        name={name}
        description={description}
        onNameChange={(n) => { setName(n); setDirty(true); }}
        onDescriptionChange={(d) => { setDescription(d); setDirty(true); }}
        onSave={handleSave}
        onRun={handleRun}
        onAutoLayout={handleAutoLayout}
        onBack={() => navigate(embedded ? `/workflows/${id}` : "/workflows")}
        onToggleSchema={() => setShowSchemaPanel((p) => !p)}
        schemaActive={showSchemaPanel}
        saving={createWorkflow.isPending || updateWorkflow.isPending}
        running={runWorkflow.isPending}
        dirty={dirty}
        compact={embedded}
      />

      <div className="flex flex-1 overflow-hidden">
        <NodePalette />

        <div ref={reactFlowWrapper} className="flex-1">
          <ReactFlow
            nodes={nodes}
            edges={edges}
            onNodesChange={handleNodesChange}
            onEdgesChange={onEdgesChange}
            onConnect={onConnect}
            onDragOver={onDragOver}
            onDrop={onDrop}
            onNodeClick={onNodeClick}
            onPaneClick={onPaneClick}
            nodeTypes={nodeTypes}
            fitView
            fitViewOptions={{ maxZoom: 0.85, padding: 0.3 }}
            deleteKeyCode="Delete"
            className="bg-background"
          >
            <Controls className="rounded-lg border border-border overflow-hidden" />
            <MiniMap
              nodeStrokeWidth={3}
              className="rounded-lg border border-border"
            />
            <Background variant={BackgroundVariant.Dots} gap={20} size={1} color="hsl(var(--border))" />
          </ReactFlow>
        </div>

        <ConfigPanel
          node={selectedNode}
          onUpdate={handleNodeUpdate}
          onClose={() => setSelectedNode(null)}
          onDelete={handleNodeDelete}
        />
      </div>

      {showSchemaPanel && (
        <div className="border-t border-border bg-card flex shrink-0 overflow-hidden">
          {/* 입력 스키마 */}
          <div className="flex-1 flex flex-col p-3 border-r border-border">
            <div className="flex justify-between items-center mb-2">
              <span className="text-xs font-semibold text-foreground">
                입력 스키마 (JSON Schema)
              </span>
            </div>
            <textarea
              value={inputSchema}
              onChange={(e) => { setInputSchema(e.target.value); setDirty(true); }}
              placeholder={'{\n  "type": "object",\n  "properties": {\n    "contextData": {\n      "type": "string",\n      "description": "검토할 데이터"\n    }\n  },\n  "required": ["contextData"]\n}'}
              className="flex-1 min-h-[180px] font-mono text-xs p-2 rounded-md border border-border bg-background text-foreground resize-none outline-none"
            />
          </div>

          {/* 출력 스키마 */}
          <div className="flex-1 flex flex-col p-3">
          <div className="flex justify-between items-center mb-2">
            <span className="text-xs font-semibold text-foreground">
              출력 스키마 (JSON Schema)
            </span>
            <div className="flex items-center gap-2">
              <select
                value=""
                onChange={(e) => {
                  const val = e.target.value;
                  if (val === "__clear__") {
                    setOutputSchema("");
                    setDirty(true);
                  } else if (val) {
                    setOutputSchema(val);
                    setDirty(true);
                  }
                }}
                className="text-[11px] py-1 px-2 rounded-md border border-border bg-background text-foreground cursor-pointer outline-none"
              >
                <option value="">프리셋 선택...</option>
                <option value="__clear__">자유 텍스트 (스키마 없음)</option>
                <option value={JSON.stringify({"type":"object","properties":{"category":{"type":"string"},"confidence":{"type":"number"}},"required":["category","confidence"]}, null, 2)}>
                  분류 결과
                </option>
                <option value={JSON.stringify({"type":"object","properties":{"summary":{"type":"string"},"key_points":{"type":"array","items":{"type":"string"}}},"required":["summary","key_points"]}, null, 2)}>
                  요약
                </option>
                <option value={JSON.stringify({"type":"object","properties":{"entities":{"type":"array","items":{"type":"object","properties":{"name":{"type":"string"},"type":{"type":"string"},"value":{"type":"string"}},"required":["name","type","value"]}}},"required":["entities"]}, null, 2)}>
                  추출 결과
                </option>
              </select>
              <button
                onClick={() => setShowSchemaPanel(false)}
                className="bg-transparent border-none cursor-pointer text-muted-foreground/40 text-sm p-0.5 hover:text-foreground"
              >
                ✕
              </button>
            </div>
          </div>
          <textarea
            value={outputSchema}
            onChange={(e) => { setOutputSchema(e.target.value); setDirty(true); }}
            placeholder={'{\n  "type": "object",\n  "properties": { ... },\n  "required": [ ... ]\n}'}
            className="flex-1 min-h-[180px] font-mono text-xs p-2 rounded-md border border-border bg-background text-foreground resize-none outline-none"
          />
          </div>
        </div>
      )}
    </div>
  );
}

export default function WorkflowStudio({ embedded }: { embedded?: boolean } = {}) {
  return (
    <ReactFlowProvider>
      <StudioInner embedded={embedded} />
    </ReactFlowProvider>
  );
}
