export interface User {
  id: string;
  username: string;
  email?: string;
  role?: string;
  roleId?: string;
  status?: "active" | "inactive";
  apiKey?: string;
  createdAt?: string;
  updatedAt?: string;
}

export interface UserRequest {
  username: string;
  email?: string;
  password?: string;
  roleId?: string;
}

export interface Role {
  id: string;
  name: string;
  description?: string;
  permissions?: string[];
  userCount?: number;
  createdAt?: string;
}

export interface RoleRequest {
  name: string;
  description?: string;
  permissions?: string[];
}
