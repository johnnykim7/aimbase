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

import { COLORS } from "../theme";
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

function StudioInner() {
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
  const [description] = useState("");
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

      const newNode: Node = {
        id: nextId(),
        type: "workflowNode",
        position,
        data: { label: label || type, type, config: {} },
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
      setSelectedNode(null);
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
    <div style={{ display: "flex", flexDirection: "column", height: "100vh", background: COLORS.bg }}>
      <StudioToolbar
        name={name}
        onNameChange={(n) => { setName(n); setDirty(true); }}
        onSave={handleSave}
        onRun={handleRun}
        onAutoLayout={handleAutoLayout}
        onBack={() => navigate("/workflows")}
        onToggleSchema={() => setShowSchemaPanel((p) => !p)}
        schemaActive={showSchemaPanel}
        saving={createWorkflow.isPending || updateWorkflow.isPending}
        running={runWorkflow.isPending}
        dirty={dirty}
      />

      <div style={{ display: "flex", flex: 1, overflow: "hidden" }}>
        <NodePalette />

        <div ref={reactFlowWrapper} style={{ flex: 1 }}>
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
            deleteKeyCode="Delete"
            style={{ background: COLORS.bg }}
          >
            <Controls
              style={{ borderRadius: 8, border: `1px solid ${COLORS.border}`, overflow: "hidden" }}
            />
            <MiniMap
              nodeStrokeWidth={3}
              style={{ borderRadius: 8, border: `1px solid ${COLORS.border}` }}
            />
            <Background variant={BackgroundVariant.Dots} gap={20} size={1} color={COLORS.border} />
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
        <div
          style={{
            height: 200,
            borderTop: `1px solid ${COLORS.border}`,
            background: COLORS.surface,
            display: "flex",
            flexDirection: "column",
            padding: 12,
            flexShrink: 0,
          }}
        >
          <div style={{ display: "flex", justifyContent: "space-between", marginBottom: 8 }}>
            <span style={{ fontSize: 12, fontWeight: 600, color: COLORS.text }}>
              워크플로우 출력 스키마 (JSON Schema)
            </span>
            <button
              onClick={() => setShowSchemaPanel(false)}
              style={{ background: "none", border: "none", cursor: "pointer", color: COLORS.textDim }}
            >
              ✕
            </button>
          </div>
          <textarea
            value={outputSchema}
            onChange={(e) => { setOutputSchema(e.target.value); setDirty(true); }}
            placeholder={'{\n  "type": "object",\n  "properties": { ... },\n  "required": [ ... ]\n}'}
            style={{
              flex: 1,
              fontFamily: "monospace",
              fontSize: 12,
              padding: 8,
              borderRadius: 6,
              border: `1px solid ${COLORS.border}`,
              background: COLORS.bg,
              color: COLORS.text,
              resize: "none",
              outline: "none",
            }}
          />
        </div>
      )}
    </div>
  );
}

export default function WorkflowStudio() {
  return (
    <ReactFlowProvider>
      <StudioInner />
    </ReactFlowProvider>
  );
}
