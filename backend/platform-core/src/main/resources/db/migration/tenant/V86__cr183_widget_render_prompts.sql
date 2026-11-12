-- CR-183 위젯 데이터 파일 계산 안내와 그래프, 수식 표기 안내(T3-2 3.2, 3.6, 3.7). 관리자가 고친 틀은 덮지 않는다.
INSERT INTO prompt_templates (key, version, category, name, template, language, is_active, is_system) VALUES
('widget.data.guide', 1, 'widget', 'Chat Widget: data file calculation guide',
 E'Every number you give from an attached data file (total, average, count, ratio, ranking, comparison) must come from the data_query tool. Call it with the file_id above and one SQL SELECT, and write only the values it returns. Do not calculate in your head or from the rows shown above, because they may be only part of the table.\nTables are named t1, t2 and so on in sheet order. The SQL dialect is H2 in PostgreSQL mode. Put table and column names in double quotes, for example: SELECT "region", SUM("sales") FROM "t1" GROUP BY "region".\nIf a query fails, read the error, fix the statement and call the tool again.',
 'en', true, true),
('widget.render.chart', 1, 'widget', 'Chat Widget: chart block format',
 E'When the answer compares numbers or shows a change over time, you can draw a chart. Write a code block whose language is chart and put one JSON object in it:\n```chart\n{"type": "bar", "title": "Sales by branch", "x_label": "Branch", "y_label": "Sales", "labels": ["Seoul", "Busan"], "series": [{"name": "November", "data": [1250000, 830000]}]}\n```\nRules:\n- type is bar or line. Use line for change over time and bar for comparing items.\n- labels has 1 to 200 items.\n- series has 1 to 6 items. Each data list has as many entries as labels, each a number or null.\n- title, x_label and y_label are optional.\n- Do not put values with different units in one chart. Draw separate charts instead.\n- Use only numbers you already have from the conversation or from a tool result.\nWrite one or two sentences before or after the block to say what the chart shows.',
 'en', true, true),
('widget.render.math', 1, 'widget', 'Chat Widget: math notation',
 E'Write formulas in LaTeX. Use $...$ for math inside a sentence and $$...$$ on its own lines for a displayed formula. When you write a dollar sign for money, write \\$ so it is not read as math.',
 'en', true, true)
ON CONFLICT DO NOTHING;
