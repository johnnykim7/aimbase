import { Navigate, Route, Routes } from "react-router-dom";
import { AppShell } from "./components/layout/AppShell";
import Dashboard from "./pages/Dashboard";
import Connections from "./pages/Connections";
import ConnectionGroups from "./pages/ConnectionGroups";
import MCPServers from "./pages/MCPServers";
import Schemas from "./pages/Schemas";
import Policies from "./pages/Policies";
import Prompts from "./pages/Prompts";
import Workflows from "./pages/Workflows";
import WorkflowStudio from "./pages/WorkflowStudio";
import Knowledge from "./pages/Knowledge";
import Auth from "./pages/Auth";
import Monitoring from "./pages/Monitoring";
import Apps from "./pages/platform/Apps";
import AppTenants from "./pages/platform/AppTenants";
import Tenants from "./pages/platform/Tenants";
import Subscriptions from "./pages/platform/Subscriptions";
import PlatformMonitoring from "./pages/platform/PlatformMonitoring";
import AgentAccounts from "./pages/platform/AgentAccounts";
import Guides from "./pages/Guides";
import Login from "./pages/Login";

export default function App() {
  return (
    <Routes>
      <Route path="login" element={<Login />} />
      <Route element={<AppShell />}>
        <Route index element={<Dashboard />} />
        <Route path="connections" element={<Connections />} />
        <Route path="connection-groups" element={<ConnectionGroups />} />
        <Route path="mcp-servers" element={<MCPServers />} />
        <Route path="schemas" element={<Schemas />} />
        <Route path="policies" element={<Policies />} />
        <Route path="prompts" element={<Prompts />} />
        <Route path="workflows" element={<Workflows />} />
        <Route path="workflows/new" element={<WorkflowStudio />} />
        <Route path="workflows/:id/edit" element={<WorkflowStudio />} />
        <Route path="knowledge" element={<Knowledge />} />
        <Route path="auth" element={<Auth />} />
        <Route path="monitoring" element={<Monitoring />} />
        <Route path="guides" element={<Guides />} />
        <Route path="platform">
          <Route index element={<Navigate to="apps" replace />} />
          <Route path="apps" element={<Apps />} />
          <Route path="apps/:appId/tenants" element={<AppTenants />} />
          <Route path="tenants" element={<Tenants />} />
          <Route path="subscriptions" element={<Subscriptions />} />
          <Route path="agent-accounts" element={<AgentAccounts />} />
          <Route path="monitoring" element={<PlatformMonitoring />} />
        </Route>
        <Route path="*" element={<Navigate to="/" replace />} />
      </Route>
    </Routes>
  );
}
