import { apiClient } from "./client";
import type { ApiResponse } from "../types/api";

export interface DocumentTemplate {
  id: string;
  name: string;
  description: string;
  format: string;
  template_type: string;
  variables: TemplateVariable[];
  tags: string[];
  code_template?: string;
  created_by: string;
  created_at: string;
  updated_at: string;
}

export interface TemplateVariable {
  name: string;
  type: string;
  default_value: string;
  required: boolean;
  description: string;
}

export interface SaveTemplateRequest {
  name: string;
  format: string;
  templateType: string;
  codeTemplate: string;
  description?: string;
  tags?: string;
  variables?: string;
}

export const documentsApi = {
  // 문서 직접 생성 (JSON 응답)
  generateJson: (code: string, outputFormat: string, outputFilename = "") =>
    apiClient.post<ApiResponse<{
      success: boolean;
      file_base64?: string;
      filename?: string;
      format?: string;
      size_bytes?: number;
      error?: string;
    }>>("/documents/generate-json", { code, outputFormat, outputFilename }),

  // 문서 직접 생성 (바이너리 다운로드)
  generateDownload: (code: string, outputFormat: string, outputFilename = "") =>
    apiClient.post("/documents/generate", { code, outputFormat, outputFilename }, {
      responseType: "blob",
    }),

  // 지원 포맷
  listFormats: () =>
    apiClient.get<ApiResponse<{ formats: Record<string, { name: string; library: string; description: string; available: boolean }> }>>("/documents/formats"),

  // 템플릿 CRUD
  saveTemplate: (data: SaveTemplateRequest) =>
    apiClient.post<ApiResponse<{ template_id: string; name: string; success: boolean }>>("/documents/templates", data),

  listTemplates: (format = "", templateType = "") =>
    apiClient.get<ApiResponse<{ templates: DocumentTemplate[]; count: number; success: boolean }>>("/documents/templates", {
      params: { format, templateType },
    }),

  getTemplate: (id: string) =>
    apiClient.get<ApiResponse<{ template: DocumentTemplate; success: boolean }>>(`/documents/templates/${id}`),

  deleteTemplate: (id: string) =>
    apiClient.delete<ApiResponse<{ success: boolean }>>(`/documents/templates/${id}`),

  // 템플릿 렌더링 (바이너리 다운로드)
  renderDownload: (templateId: string, variables: Record<string, unknown>, outputFormat = "") =>
    apiClient.post(`/documents/templates/${templateId}/render`,
      { variables: JSON.stringify(variables), outputFormat },
      { responseType: "blob" },
    ),

  // 파일 업로드 템플릿
  uploadTemplate: (file: File, name: string, description = "", tags = "[]") => {
    const formData = new FormData();
    formData.append("file", file);
    formData.append("name", name);
    formData.append("description", description);
    formData.append("tags", tags);
    return apiClient.post<ApiResponse<{
      template_id: string; name: string; variables: TemplateVariable[];
      variable_count: number; file_size_bytes: number; success: boolean; error?: string;
    }>>("/documents/templates/upload", formData, {
      headers: { "Content-Type": "multipart/form-data" },
    });
  },

  // 템플릿 렌더링 (JSON)
  renderJson: (templateId: string, variables: Record<string, unknown>, outputFormat = "") =>
    apiClient.post<ApiResponse<{
      success: boolean;
      file_base64?: string;
      filename?: string;
      format?: string;
      size_bytes?: number;
      template_name?: string;
      error?: string;
    }>>(`/documents/templates/${templateId}/render-json`,
      { variables: JSON.stringify(variables), outputFormat },
    ),
};
