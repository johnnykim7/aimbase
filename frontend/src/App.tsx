import { Navigate, Route, Routes } from "react-router-dom";
import { AppShell } from "./components/layout/AppShell";
import Login from "./pages/Login";
import Dashboard from "./pages/Dashboard";
import Connections from "./pages/Connections";
import MCPServers from "./pages/MCPServers";
import Schemas from "./pages/Schemas";
import Policies from "./pages/Policies";
import Prompts from "./pages/Prompts";
import Workflows from "./pages/Workflows";
import WorkflowStudio from "./pages/WorkflowStudio";
import WorkflowDetail from "./pages/WorkflowDetail";
import Knowledge from "./pages/Knowledge";
import RagEvaluation from "./pages/RagEvaluation";
import Documents from "./pages/Documents";
import Auth from "./pages/Auth";
import Monitoring from "./pages/Monitoring";
import Projects from "./pages/Projects";
import Sessions from "./pages/Sessions";
import SessionDetail from "./pages/SessionDetail";
import ContextRecipes from "./pages/ContextRecipes";
import DomainConfigs from "./pages/DomainConfigs";
import ScheduledJobs from "./pages/ScheduledJobs";
import Skills from "./pages/Skills";
import PromptTemplates from "./pages/PromptTemplates";
import Tenants from "./pages/platform/Tenants";
import Subscriptions from "./pages/platform/Subscriptions";
import PlatformMonitoring from "./pages/platform/PlatformMonitoring";
import ApiKeys from "./pages/platform/ApiKeys";

export default function App() {
  return (
    <Routes>
      <Route path="login" element={<Login />} />
      <Route element={<AppShell />}>
        <Route index element={<Dashboard />} />
        <Route path="connections" element={<Connections />} />
        <Route path="mcp-servers" element={<MCPServers />} />
        <Route path="schemas" element={<Schemas />} />
        <Route path="policies" element={<Policies />} />
        <Route path="prompts" element={<Prompts />} />
        <Route path="workflows" element={<Workflows />} />
        <Route path="workflows/new" element={<WorkflowStudio />} />
        <Route path="workflows/:id" element={<WorkflowDetail />} />
        <Route path="workflows/:id/edit" element={<WorkflowStudio />} />
        <Route path="knowledge" element={<Knowledge />} />
        <Route path="rag-evaluation" element={<RagEvaluation />} />
        <Route path="documents" element={<Documents />} />
        <Route path="projects" element={<Projects />} />
        <Route path="auth" element={<Auth />} />
        <Route path="sessions" element={<Sessions />} />
        <Route path="sessions/:id" element={<SessionDetail />} />
        <Route path="context-recipes" element={<ContextRecipes />} />
        <Route path="domain-configs" element={<DomainConfigs />} />
        <Route path="scheduled-jobs" element={<ScheduledJobs />} />
        <Route path="skills" element={<Skills />} />
        <Route path="prompt-templates" element={<PromptTemplates />} />
        <Route path="monitoring" element={<Monitoring />} />
        <Route path="platform">
          <Route index element={<Navigate to="tenants" replace />} />
          <Route path="tenants" element={<Tenants />} />
          <Route path="subscriptions" element={<Subscriptions />} />
          <Route path="api-keys" element={<ApiKeys />} />
          <Route path="monitoring" element={<PlatformMonitoring />} />
        </Route>
        <Route path="*" element={<Navigate to="/" replace />} />
      </Route>
    </Routes>
  );
}
