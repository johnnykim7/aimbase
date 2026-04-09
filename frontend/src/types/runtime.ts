export interface RuntimeCapability {
  supportsStreaming?: boolean;
  supportsToolUse?: boolean;
  supportsMultiTurn?: boolean;
  supportsLongContext?: boolean;
  supportsAutonomousExploration?: boolean;
  supportsStructuredOutput?: boolean;
  maxContextTokens?: number;
  supportedModels?: string[];
  strengths?: string[];
}

export interface RuntimeSelectionCriteria {
  preferredRuntime?: string;
  requiresToolUse?: boolean;
  requiresLongContext?: boolean;
  requiresAutonomous?: boolean;
  taskType?: string;
}

export interface RuntimeResult {
  runtimeId: string;
  selectionReason?: string;
  totalDurationMs?: number;
}
