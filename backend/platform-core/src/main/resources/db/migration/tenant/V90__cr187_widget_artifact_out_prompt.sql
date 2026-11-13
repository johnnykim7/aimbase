-- CR-187 실행기 연결 대화의 파일 위치 안내. 대화 파일 카드는 세션 작업 폴더의 out/ 만 훑는다. 관리자가 고친 틀은 덮지 않는다.
INSERT INTO prompt_templates (key, version, category, name, template, language, is_active, is_system) VALUES
('widget.artifact-out', 1, 'widget', 'Chat Widget: where to save files for the user',
 E'When you make a file for the user (docx, xlsx, pptx, pdf, csv, an image and so on), save it in ./out/ under the current working directory. Run mkdir -p ./out first.\nOnly files in ./out/ reach the user as file cards they can open and download. A file saved anywhere else, such as /tmp, never reaches the user.\nTo change a file you made earlier, write the new version to the same name in ./out/.\nIn your answer, name the file only. Do not show its full path.',
 'en', true, true)
ON CONFLICT DO NOTHING;
