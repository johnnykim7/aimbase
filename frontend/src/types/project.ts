export interface Project {
  id: string;
  name: string;
  description?: string;
  isActive: boolean;
  createdAt: string;
  updatedAt?: string;
}

export interface ProjectDetail extends Project {
  members: ProjectMember[];
  resourceCount: number;
  resources: ProjectResource[];
}

export interface ProjectMember {
  id: string;
  projectId: string;
  userId: string;
  role: string;
  createdAt: string;
}

export interface ProjectResource {
  id: string;
  projectId: string;
  resourceType: string;
  resourceId: string;
  createdAt: string;
}

export interface ProjectRequest {
  id?: string;
  name: string;
  description?: string;
}

export interface MemberRequest {
  userId: string;  // users.id (VARCHAR, not UUID)
  role?: string;
}

export interface ResourceRequest {
  resourceType: string;
  resourceId: string;
}
