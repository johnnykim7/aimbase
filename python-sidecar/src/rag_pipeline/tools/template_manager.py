"""CR-018: Document Template Manager.

문서 템플릿 CRUD + 변수 치환 기반 문서 생성.
- code 타입: Python 코드 템플릿에 변수 주입 후 실행
- file 타입: PPTX/DOCX 파일의 플레이스홀더를 치환
"""

import json
import logging
import os
import re
import uuid
from typing import Any

from rag_pipeline.db import get_connection

logger = logging.getLogger(__name__)


def save_template(
    name: str,
    format: str,
    template_type: str = "code",
    code_template: str = "",
    variables: str = "[]",
    description: str = "",
    tags: str = "[]",
    created_by: str = "",
) -> dict[str, Any]:
    """템플릿 저장.

    Args:
        name: 템플릿 이름
        format: 출력 포맷 (pptx, docx, pdf, xlsx, csv, html, png, jpg, svg)
        template_type: 'code' 또는 'file'
        code_template: Python 코드 템플릿 (code 타입)
        variables: JSON 배열 문자열 [{name, type, default_value, required, description}]
        description: 설명
        tags: JSON 배열 문자열 ["tag1", "tag2"]
        created_by: 생성자

    Returns:
        {template_id, name, success}
    """
    template_id = str(uuid.uuid4())

    # variables 파싱
    try:
        vars_list = json.loads(variables) if isinstance(variables, str) else variables
    except json.JSONDecodeError:
        vars_list = []

    try:
        tags_list = json.loads(tags) if isinstance(tags, str) else tags
    except json.JSONDecodeError:
        tags_list = []

    # code 타입이면 코드에서 변수 자동 추출 ({{var_name}} 패턴)
    if template_type == "code" and code_template and not vars_list:
        vars_list = _extract_variables_from_code(code_template)

    conn = get_connection()
    try:
        with conn.cursor() as cur:
            cur.execute(
                """
                INSERT INTO document_templates
                    (id, name, description, format, template_type, code_template, variables, tags, created_by)
                VALUES (%s, %s, %s, %s, %s, %s, %s::jsonb, %s::jsonb, %s)
                """,
                (
                    template_id, name, description, format, template_type,
                    code_template, json.dumps(vars_list, ensure_ascii=False),
                    json.dumps(tags_list, ensure_ascii=False), created_by,
                ),
            )
        conn.commit()
        logger.info("Template saved: %s (%s)", name, template_id)
        return {"template_id": template_id, "name": name, "success": True}
    except Exception as e:
        conn.rollback()
        logger.error("Failed to save template: %s", e)
        return {"success": False, "error": str(e)}


def list_templates(
    format: str = "",
    template_type: str = "",
) -> dict[str, Any]:
    """템플릿 목록 조회.

    Args:
        format: 포맷 필터 (빈 문자열이면 전체)
        template_type: 타입 필터 ('code' | 'file', 빈 문자열이면 전체)

    Returns:
        {templates: [...], success}
    """
    conn = get_connection()
    try:
        with conn.cursor() as cur:
            query = """
                SELECT id, name, description, format, template_type,
                       variables, tags, created_by, created_at, updated_at
                FROM document_templates
                WHERE 1=1
            """
            params: list[Any] = []
            if format:
                query += " AND format = %s"
                params.append(format)
            if template_type:
                query += " AND template_type = %s"
                params.append(template_type)
            query += " ORDER BY updated_at DESC"

            cur.execute(query, params)
            rows = cur.fetchall()

            templates = []
            for row in rows:
                templates.append({
                    "id": row[0],
                    "name": row[1],
                    "description": row[2],
                    "format": row[3],
                    "template_type": row[4],
                    "variables": row[5] if row[5] else [],
                    "tags": row[6] if row[6] else [],
                    "created_by": row[7],
                    "created_at": str(row[8]) if row[8] else None,
                    "updated_at": str(row[9]) if row[9] else None,
                })

        return {"templates": templates, "count": len(templates), "success": True}
    except Exception as e:
        logger.error("Failed to list templates: %s", e)
        return {"success": False, "error": str(e)}


def get_template(template_id: str) -> dict[str, Any]:
    """템플릿 상세 조회 (코드 포함).

    Args:
        template_id: 템플릿 ID

    Returns:
        {template: {...}, success}
    """
    conn = get_connection()
    try:
        with conn.cursor() as cur:
            cur.execute(
                """
                SELECT id, name, description, format, template_type,
                       code_template, file_path, variables, tags,
                       created_by, created_at, updated_at
                FROM document_templates WHERE id = %s
                """,
                (template_id,),
            )
            row = cur.fetchone()

        if not row:
            return {"success": False, "error": f"Template not found: {template_id}"}

        return {
            "template": {
                "id": row[0],
                "name": row[1],
                "description": row[2],
                "format": row[3],
                "template_type": row[4],
                "code_template": row[5],
                "file_path": row[6],
                "variables": row[7] if row[7] else [],
                "tags": row[8] if row[8] else [],
                "created_by": row[9],
                "created_at": str(row[10]) if row[10] else None,
                "updated_at": str(row[11]) if row[11] else None,
            },
            "success": True,
        }
    except Exception as e:
        logger.error("Failed to get template: %s", e)
        return {"success": False, "error": str(e)}


def delete_template(template_id: str) -> dict[str, Any]:
    """템플릿 삭제.

    Args:
        template_id: 템플릿 ID

    Returns:
        {success, deleted_id}
    """
    conn = get_connection()
    try:
        with conn.cursor() as cur:
            cur.execute("DELETE FROM document_templates WHERE id = %s RETURNING id", (template_id,))
            deleted = cur.fetchone()
        conn.commit()

        if not deleted:
            return {"success": False, "error": f"Template not found: {template_id}"}

        return {"success": True, "deleted_id": template_id}
    except Exception as e:
        conn.rollback()
        return {"success": False, "error": str(e)}


def render_template(
    template_id: str,
    variables: str = "{}",
) -> dict[str, Any]:
    """템플릿으로 문서 생성.

    템플릿의 코드에서 {{변수명}}을 치환한 뒤 generate_document로 실행.

    Args:
        template_id: 템플릿 ID
        variables: JSON 객체 문자열 {"title": "보고서", "items": [...]}

    Returns:
        generate_document 결과와 동일
    """
    from rag_pipeline.tools.document_generator import generate_document

    # 변수 파싱
    try:
        var_values = json.loads(variables) if isinstance(variables, str) else variables
    except json.JSONDecodeError:
        return {"success": False, "error": "Invalid variables JSON"}

    # 템플릿 로드
    tpl_result = get_template(template_id)
    if not tpl_result.get("success"):
        return tpl_result

    tpl = tpl_result["template"]

    if tpl["template_type"] != "code":
        return {"success": False, "error": "Only 'code' type templates are supported for rendering"}

    code_template = tpl.get("code_template", "")
    if not code_template:
        return {"success": False, "error": "Template has no code_template"}

    # 변수 치환: {{var_name}} → 실제 값
    rendered_code = _render_code(code_template, var_values)

    # 문서 생성 실행
    result = generate_document(
        code=rendered_code,
        output_format=tpl["format"],
    )
    result["template_id"] = template_id
    result["template_name"] = tpl["name"]
    return result


def _render_code(code_template: str, variables: dict[str, Any]) -> str:
    """코드 템플릿의 {{var_name}} 플레이스홀더를 변수값으로 치환."""
    rendered = code_template
    for key, value in variables.items():
        placeholder = "{{" + key + "}}"
        if isinstance(value, (dict, list)):
            replacement = json.dumps(value, ensure_ascii=False)
        else:
            replacement = str(value)
        rendered = rendered.replace(placeholder, replacement)
    return rendered


def _extract_variables_from_code(code: str) -> list[dict[str, Any]]:
    """코드에서 {{var_name}} 패턴의 변수를 자동 추출."""
    pattern = r"\{\{(\w+)\}\}"
    found = set(re.findall(pattern, code))
    return [
        {"name": var, "type": "string", "default_value": "", "required": True, "description": ""}
        for var in sorted(found)
    ]
