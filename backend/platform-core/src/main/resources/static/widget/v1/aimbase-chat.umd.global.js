"use strict";var AimbaseChat=(()=>{var ce=Object.defineProperty;var ze=Object.getOwnPropertyDescriptor;var Ne=Object.getOwnPropertyNames;var He=Object.prototype.hasOwnProperty;var Be=(n,t)=>{for(var s in t)ce(n,s,{get:t[s],enumerable:!0})},Fe=(n,t,s,i)=>{if(t&&typeof t=="object"||typeof t=="function")for(let c of Ne(t))!He.call(n,c)&&c!==s&&ce(n,c,{get:()=>t[c],enumerable:!(i=ze(t,c))||i.enumerable});return n};var Je=n=>Fe(ce({},"__esModule",{value:!0}),n);var et={};Be(et,{AimbaseChatElement:()=>N,SttClient:()=>W,SttError:()=>T,createWidget:()=>P,defineAimbaseChat:()=>te,init:()=>Qe});var Y=class{constructor(t){this.tokens=t}async upload(t,s){let i=await this.tokens.getToken(),c=new FormData;return c.append("session_id",t.sessionId),c.append("file",t.file,t.file.name),new Promise((v,b)=>{let f=new XMLHttpRequest;f.open("POST",`${t.baseUrl}/api/v1/chat/attachments`),f.setRequestHeader("Authorization",`Bearer ${i}`),s&&f.upload&&f.upload.addEventListener("progress",o=>{if(o.lengthComputable){let m=Math.round(o.loaded/o.total*100);s(m)}}),f.onload=()=>{if(f.status>=200&&f.status<300)try{let o=JSON.parse(f.responseText),m=o.data??o;v(m)}catch(o){b(new Error(`invalid response JSON: ${o.message}`))}else{let o=je(f.responseText)??`status ${f.status}`;b(new Error(o))}},f.onerror=()=>b(new Error("network error")),f.send(c)})}async delete(t){let s=await this.tokens.getToken(),i=`${t.baseUrl}/api/v1/chat/attachments/${encodeURIComponent(t.attachmentId)}?session_id=${encodeURIComponent(t.sessionId)}`,c=await fetch(i,{method:"DELETE",headers:{Authorization:`Bearer ${s}`}});if(!c.ok&&c.status!==404){let v=await c.text().catch(()=>"");throw new Error(`delete attachment ${c.status}: ${v.slice(0,200)}`)}}};function je(n){if(!n)return null;try{let t=JSON.parse(n);return typeof t.error=="string"?t.error:typeof t.message=="string"?t.message:null}catch{return n.slice(0,200)}}async function*ve(n,t){if(!n.body)throw new Error("SSE response has no body");let s=n.body.getReader(),i=new TextDecoder("utf-8"),c="",v="message";try{for(;!t?.aborted;){let{value:b,done:f}=await s.read();if(f)break;c+=i.decode(b,{stream:!0});let o;for(;(o=c.indexOf(`

`))!==-1;){let m=c.slice(0,o);c=c.slice(o+2);let d="";for(let g of m.split(`
`))g.startsWith("event:")?v=g.slice(6).trim()||"message":g.startsWith("data:")?d+=g.slice(5).replace(/^ /,""):g.startsWith(":");d&&(yield{name:v,data:d},v="message")}}}finally{try{s.releaseLock()}catch{}}}var Z=class{constructor(t){this.tokens=t;this.currentAbort=null}async sendMessage(t,s){let i=this.currentAbort,c=new AbortController;this.currentAbort=c,i?.abort();let v=await this.tokens.getToken(),b=[];for(let d of t.attachments??[])b.push({type:d.mediaType==="application/pdf"?"document":"image",attachment_id:d.attachmentId});t.text&&t.text.length>0&&b.push({type:"text",text:t.text});let f=[];t.context&&Object.keys(t.context).length>0&&f.push({role:"system",content:[{type:"text",text:`# \uD604\uC7AC \uD654\uBA74 \uCEE8\uD14D\uC2A4\uD2B8
${JSON.stringify(t.context,null,2)}`}]}),f.push({role:"user",content:b});let o={model:t.model??"auto",session_id:t.sessionId,stream:!0,messages:f};t.ragSourceId&&(o.rag_source_id=t.ragSourceId),t.connectionId&&(o.connection_id=t.connectionId);let m;try{m=await fetch(`${t.baseUrl}/api/v1/chat/completions`,{method:"POST",headers:{Authorization:`Bearer ${v}`,"Content-Type":"application/json",Accept:"text/event-stream"},body:JSON.stringify(o),signal:c.signal})}catch(d){if(d?.name==="AbortError"||c.signal.aborted)return;throw d}if(!m.ok){let d=await m.text().catch(()=>"");throw new Error(`chat/completions ${m.status}: ${d.slice(0,200)}`)}try{for await(let d of ve(m,c.signal))try{let g=JSON.parse(d.data);switch(d.name){case"delta":s({type:"delta",text:g.delta??""});break;case"thinking":s({type:"thinking",text:g.delta??""});break;case"tool_use_start":s({type:"tool_use_start",tool:{id:g.id,name:g.name,input:g.input}});break;case"tool_result":s({type:"tool_result",toolResult:{tool_use_id:g.tool_use_id,output:g.output,is_error:!!g.is_error}});break;case"done":s({type:"done",done:{rag_used:!!g.rag_used,citations:Array.isArray(g.citations)?g.citations:[]}});break;default:break}}catch(g){s({type:"error",error:`parse error: ${g.message}`})}}catch(d){if(d?.name==="AbortError"||c.signal.aborted)return;throw d}}async abort(t,s){this.currentAbort?.abort(),this.currentAbort=null;let i=await this.tokens.getToken();try{await fetch(`${t}/api/v1/chat/${encodeURIComponent(s)}/abort`,{method:"POST",headers:{Authorization:`Bearer ${i}`}})}catch{}}};var W=class{constructor(t){this.tokens=t}async transcribe(t,s){let i=await this.tokens.getToken(),c=Ke(t.blob.type),v=t.filename??`rec.${c}`,b=new FormData;return b.append("session_id",t.sessionId),b.append("file",t.blob,v),t.language&&b.append("language",t.language),new Promise((f,o)=>{let m=new XMLHttpRequest;m.open("POST",`${t.baseUrl}/api/v1/chat/stt`),m.setRequestHeader("Authorization",`Bearer ${i}`),s&&m.upload&&m.upload.addEventListener("progress",d=>{d.lengthComputable&&s(Math.round(d.loaded/d.total*100))}),m.onload=()=>{if(m.status>=200&&m.status<300)try{let d=JSON.parse(m.responseText);f(d.data??d)}catch(d){o(new T("STT_INVALID_RESPONSE",`invalid JSON: ${d.message}`,m.status))}else{let{code:d,message:g}=qe(m.responseText,m.status);o(new T(d,g,m.status))}},m.onerror=()=>o(new T("STT_NETWORK","network error",0)),m.onabort=()=>o(new T("STT_ABORTED","aborted",0)),m.send(b)})}},T=class extends Error{constructor(s,i,c){super(i);this.code=s;this.status=c;this.name="SttError"}};function qe(n,t){if(!n)return{code:"STT_UNKNOWN",message:`status ${t}`};try{let s=JSON.parse(n),i=s.error??s.message;if(i){let c=i.match(/^(STT_[A-Z_]+):\s*(.*)$/);return c?{code:c[1],message:c[2]}:{code:"STT_UNKNOWN",message:i}}}catch{}return{code:"STT_UNKNOWN",message:n.slice(0,200)}}function Ke(n){switch((n||"").toLowerCase().split(";")[0].trim()){case"audio/webm":return"webm";case"audio/mp4":return"mp4";case"audio/mpeg":return"mp3";case"audio/wav":return"wav";case"audio/ogg":return"ogg";default:return"bin"}}var xe=`
:host {
  --aimbase-primary: #4f46e5;
  --aimbase-primary-hover: #4338ca;
  --aimbase-bg: #ffffff;
  --aimbase-fg: #111827;
  --aimbase-muted: #6b7280;
  --aimbase-border: #e5e7eb;
  --aimbase-user-bg: #eef2ff;
  --aimbase-assistant-bg: #f9fafb;
  --aimbase-citation-bg: #fef3c7;
  --aimbase-radius: 10px;
  --aimbase-font: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, "Noto Sans KR", sans-serif;
  font-family: var(--aimbase-font);
  color: var(--aimbase-fg);
  font-size: 14px;
}
:host([data-theme="dark"]) {
  --aimbase-bg: #1f2937;
  --aimbase-fg: #f3f4f6;
  --aimbase-muted: #9ca3af;
  --aimbase-border: #374151;
  --aimbase-user-bg: #312e81;
  --aimbase-assistant-bg: #111827;
}

.root { box-sizing: border-box; }
*, *::before, *::after { box-sizing: border-box; }

/* \uBC84\uBE14 \uBAA8\uB4DC */
.bubble-btn {
  position: fixed; bottom: 20px; right: 20px;
  width: 56px; height: 56px; border-radius: 50%;
  background: var(--aimbase-primary); color: white;
  border: none; cursor: pointer; box-shadow: 0 4px 12px rgba(0,0,0,.15);
  font-size: 24px; z-index: 9998;
}
.bubble-btn:hover { background: var(--aimbase-primary-hover); }

.panel {
  background: var(--aimbase-bg);
  border: 1px solid var(--aimbase-border);
  border-radius: var(--aimbase-radius);
  display: flex; flex-direction: column;
  overflow: hidden;
}
.panel.floating {
  position: fixed; bottom: 90px; right: 20px;
  width: 380px; height: 560px; max-height: 80vh;
  box-shadow: 0 10px 30px rgba(0,0,0,.18);
  z-index: 9999;
}
.panel.inline { width: 100%; height: 100%; min-height: 400px; }
.panel.side { position: fixed; top: 0; right: 0; bottom: 0; width: 400px; max-width: 100vw; z-index: 9999; }
.panel.hidden { display: none; }

.header {
  display: flex; align-items: center; justify-content: space-between;
  padding: 12px 16px; border-bottom: 1px solid var(--aimbase-border);
  font-weight: 600;
}
.header .close { background: none; border: none; font-size: 20px; cursor: pointer; color: var(--aimbase-muted); }

.messages { flex: 1; overflow-y: auto; padding: 12px; display: flex; flex-direction: column; gap: 10px; }

.msg {
  padding: 10px 12px; border-radius: 8px; max-width: 85%;
  white-space: pre-wrap; word-break: break-word; line-height: 1.5;
}
.msg.user { align-self: flex-end; background: var(--aimbase-user-bg); }
.msg.assistant { align-self: flex-start; background: var(--aimbase-assistant-bg); border: 1px solid var(--aimbase-border); }
.msg.thinking { align-self: flex-start; background: transparent; color: var(--aimbase-muted); font-style: italic; font-size: 12px; }
.msg.tool { align-self: flex-start; background: transparent; color: var(--aimbase-muted); font-size: 12px; border-left: 3px solid var(--aimbase-primary); padding-left: 8px; }
.msg.error { align-self: center; background: #fee2e2; color: #991b1b; font-size: 12px; }

.citations {
  align-self: flex-start; max-width: 100%;
  display: flex; flex-wrap: wrap; gap: 6px;
  margin-top: -4px;
}
.citation {
  background: var(--aimbase-citation-bg);
  border: 1px solid #f59e0b;
  color: #78350f;
  padding: 4px 10px; border-radius: 12px;
  font-size: 12px; cursor: pointer;
}
.citation:hover { background: #fde68a; }

.workflow-panel {
  margin: 8px 12px; padding: 10px;
  border: 1px solid var(--aimbase-border);
  border-radius: 8px; font-size: 12px;
  background: var(--aimbase-assistant-bg);
}
.workflow-step {
  display: flex; align-items: center; gap: 8px;
  padding: 4px 0;
}
.workflow-step .dot { width: 8px; height: 8px; border-radius: 50%; }
.workflow-step .dot.running { background: #3b82f6; animation: pulse 1.2s infinite; }
.workflow-step .dot.completed { background: #10b981; }
.workflow-step .dot.failed { background: #ef4444; }
@keyframes pulse { 0%,100% { opacity: 1; } 50% { opacity: .5; } }

.composer {
  display: flex; gap: 8px; padding: 10px;
  border-top: 1px solid var(--aimbase-border);
}
.composer textarea {
  flex: 1; resize: none; padding: 8px 10px;
  border: 1px solid var(--aimbase-border);
  border-radius: 6px; font-family: inherit; font-size: 13px;
  background: var(--aimbase-bg); color: var(--aimbase-fg);
  min-height: 36px; max-height: 120px;
}
.composer button {
  background: var(--aimbase-primary); color: white; border: none;
  border-radius: 6px; padding: 0 14px; cursor: pointer; font-size: 13px;
}
.composer button:disabled { opacity: .5; cursor: not-allowed; }

.citation-preview {
  position: absolute; right: 0; top: 0; bottom: 0; width: 60%;
  background: var(--aimbase-bg);
  border-left: 1px solid var(--aimbase-border);
  padding: 16px; overflow-y: auto;
  box-shadow: -4px 0 12px rgba(0,0,0,.1);
}
.citation-preview h4 { margin: 0 0 8px; font-size: 14px; }
.citation-preview .meta { font-size: 12px; color: var(--aimbase-muted); margin-bottom: 10px; }
.citation-preview .content { font-size: 13px; line-height: 1.6; white-space: pre-wrap; }
.citation-preview .close { position: absolute; top: 8px; right: 8px; background: none; border: none; font-size: 18px; cursor: pointer; }

/* CR-061: \uCCA8\uBD80 UI */
.attach-btn {
  background: transparent; color: var(--aimbase-muted);
  border: 1px solid var(--aimbase-border); border-radius: 6px;
  width: 34px; height: 34px; padding: 0;
  display: inline-flex; align-items: center; justify-content: center;
  cursor: pointer; font-size: 16px; line-height: 1; flex-shrink: 0;
}
.attach-btn:hover { color: var(--aimbase-primary); border-color: var(--aimbase-primary); }
.attach-btn:disabled { opacity: .5; cursor: not-allowed; }

.attachments {
  display: flex; flex-wrap: wrap; gap: 6px;
  padding: 6px 10px 0;
}
.attachments:empty { padding: 0; }
.chip {
  display: inline-flex; align-items: center; gap: 6px;
  padding: 4px 8px 4px 4px;
  background: var(--aimbase-assistant-bg);
  border: 1px solid var(--aimbase-border);
  border-radius: 6px;
  max-width: 220px;
  font-size: 12px;
}
.chip.uploading { opacity: .75; }
.chip.error { border-color: #ef4444; color: #ef4444; }
.chip-thumb {
  width: 28px; height: 28px; border-radius: 4px;
  object-fit: cover;
  background: var(--aimbase-border);
  flex-shrink: 0;
}
.chip-thumb.pdf {
  display: inline-flex; align-items: center; justify-content: center;
  color: var(--aimbase-primary); font-weight: 700; font-size: 10px;
  background: var(--aimbase-assistant-bg);
  border: 1px solid var(--aimbase-border);
}
.chip-label {
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
  flex: 1; min-width: 0;
}
.chip-remove {
  background: none; border: none; cursor: pointer;
  color: var(--aimbase-muted); font-size: 14px; padding: 0 2px; line-height: 1;
}
.chip-remove:hover { color: #ef4444; }
.chip-spinner {
  width: 10px; height: 10px; border: 2px solid var(--aimbase-border);
  border-top-color: var(--aimbase-primary); border-radius: 50%;
  animation: chip-spin 0.8s linear infinite; flex-shrink: 0;
}
@keyframes chip-spin { to { transform: rotate(360deg); } }

.drop-overlay {
  position: absolute; inset: 0;
  border: 2px dashed var(--aimbase-primary);
  background: rgba(79, 70, 229, 0.08);
  border-radius: var(--aimbase-radius);
  display: flex; align-items: center; justify-content: center;
  font-size: 13px; color: var(--aimbase-primary); font-weight: 500;
  pointer-events: none; z-index: 10;
}

/* CR-060: \uB9C8\uC774\uD06C \uBC84\uD2BC & \uB179\uC74C \uC624\uBC84\uB808\uC774 */
.mic-btn {
  background: none; border: 1px solid var(--aimbase-border);
  border-radius: 999px; width: 32px; height: 32px; cursor: pointer;
  font-size: 14px; line-height: 1;
  display: inline-flex; align-items: center; justify-content: center;
  flex-shrink: 0; color: var(--aimbase-muted);
  transition: background 120ms ease, color 120ms ease, border-color 120ms ease;
}
.mic-btn:hover { color: var(--aimbase-primary); border-color: var(--aimbase-primary); }
.mic-btn[aria-pressed="true"] {
  background: #ef4444; color: #fff; border-color: #ef4444;
}
.mic-btn:disabled { opacity: 0.4; cursor: not-allowed; }

.recording-overlay {
  position: absolute; left: 12px; right: 12px; bottom: 64px;
  background: var(--aimbase-surface); border: 1px solid #ef4444;
  border-radius: var(--aimbase-radius);
  padding: 10px 12px; display: flex; align-items: center; gap: 10px;
  z-index: 11; box-shadow: 0 4px 12px rgba(0, 0, 0, 0.08);
}
.recording-overlay.hidden { display: none; }
.rec-wave {
  flex: 1; font-family: monospace; color: #ef4444; font-size: 14px;
  letter-spacing: 1px;
  animation: rec-pulse 0.9s ease-in-out infinite;
}
.rec-timer {
  font-variant-numeric: tabular-nums; color: var(--aimbase-text);
  font-size: 13px; font-weight: 600;
}
.rec-cancel, .rec-stop {
  background: none; border: 1px solid var(--aimbase-border);
  border-radius: var(--aimbase-radius); padding: 4px 10px;
  font-size: 12px; cursor: pointer; color: var(--aimbase-text);
}
.rec-cancel:hover { color: var(--aimbase-muted); }
.rec-stop {
  border-color: #ef4444; color: #ef4444; font-weight: 600;
}
.rec-stop:hover { background: #ef4444; color: #fff; }
@keyframes rec-pulse {
  0%, 100% { opacity: 0.55; }
  50%      { opacity: 1; }
}
`;var Q=class{constructor(t,s){this.resolver=t;this.token=null;this.expiresAtMs=0;this.refreshTimer=null;this.onExpiring=s}async getToken(){return(!this.token||Date.now()>=this.expiresAtMs-3e4)&&await this.refresh(),this.token.token}hasScope(t){return!!this.token?.scopes?.includes(t)}async ensureLoaded(){this.token||await this.refresh()}async refresh(){let t=await this.resolver();this.token=t,this.expiresAtMs=typeof t.expires_at=="number"?t.expires_at:new Date(t.expires_at).getTime(),this.scheduleRefresh(t.refresh_after)}scheduleRefresh(t){this.refreshTimer&&clearTimeout(this.refreshTimer);let s=Math.max(1e4,t*1e3);this.refreshTimer=setTimeout(()=>{try{this.onExpiring?.()}catch{}this.refresh().catch(()=>{})},s)}destroy(){this.refreshTimer&&clearTimeout(this.refreshTimer),this.refreshTimer=null,this.token=null}};var ee=class{constructor(t){this.tokens=t}async subscribe(t,s,i){let c=await this.tokens.getToken(),v=`${t}/api/v1/workflows/runs/${encodeURIComponent(s)}/subscribe?access_token=${encodeURIComponent(c)}`,b=new EventSource(v);return b.addEventListener("workflow.snapshot",f=>{try{i.onSnapshot?.(JSON.parse(f.data))}catch(o){i.onError?.(o)}}),b.addEventListener("workflow.step",f=>{try{i.onStep?.(JSON.parse(f.data))}catch(o){i.onError?.(o)}}),b.addEventListener("workflow.approval",f=>{try{i.onApproval?.(JSON.parse(f.data))}catch(o){i.onError?.(o)}}),b.addEventListener("workflow.done",f=>{try{i.onDone?.(JSON.parse(f.data))}catch(o){i.onError?.(o)}finally{b.close()}}),b.onerror=()=>{b.readyState===EventSource.CLOSED&&i.onError?.(new Error("workflow SSE closed"))},()=>b.close()}async fetchChunk(t,s,i){let c=await this.tokens.getToken(),v=await fetch(`${t}/api/v1/knowledge-sources/${encodeURIComponent(s)}/chunks/${encodeURIComponent(i)}`,{headers:{Authorization:`Bearer ${c}`}});if(!v.ok)throw new Error(`chunks ${v.status}`);return(await v.json()).data??{}}};var Ge="image/png,image/jpeg,image/gif,image/webp,application/pdf",Ve=10*1024*1024,Xe=32*1024*1024;function u(n,t,s){let i=document.createElement(n);return t&&(i.className=t),s&&(i.textContent=s),i}function Ye(){try{return self.crypto.randomUUID()}catch{return`sess-${Date.now()}-${Math.random().toString(36).slice(2,10)}`}}function P(n){let t=n.display??"bubble",s=n.theme?.mode??"auto",i=document.createElement("div");i.setAttribute("data-aimbase-widget","true"),s==="dark"&&i.setAttribute("data-theme","dark"),s==="light"&&i.setAttribute("data-theme","light");let c=typeof n.target=="string"?document.querySelector(n.target):n.target??null;if((t==="inline"||t==="panel")&&!c)throw new Error(`display='${t}' requires options.target`);(c??document.body).appendChild(i);let v=i.attachShadow({mode:"open"}),b=document.createElement("style");if(b.textContent=xe,v.appendChild(b),n.theme?.cssVars){let e=Object.entries(n.theme.cssVars).map(([a,l])=>`${a}: ${l};`).join(" "),r=document.createElement("style");r.textContent=`:host { ${e} }`,v.appendChild(r)}let f=u("div","root");v.appendChild(f);let o={open:t!=="bubble",sessionId:n.sessionId??Ye(),ragSourceId:n.ragSourceId,currentAssistantDiv:null,currentAssistantText:"",workflowRuns:new Map,attachments:[]},m=null;t==="bubble"&&(m=u("button","bubble-btn","\u{1F4AC}"),m.setAttribute("aria-label","Open Aimbase chat"),m.addEventListener("click",()=>j(!0)),f.appendChild(m));let d=u("div",`panel ${Ze(t)} ${o.open?"":"hidden"}`);f.appendChild(d);let g=u("div","header");if(g.appendChild(u("div","title","Aimbase Chat")),t==="bubble"||t==="panel"){let e=u("button","close","\xD7");e.addEventListener("click",()=>j(!1)),g.appendChild(e)}d.appendChild(g);let S=u("div","messages");d.appendChild(S);let re=u("div","attachments");d.appendChild(re);let L=u("div","composer"),H=u("button","attach-btn","\u{1F4CE}");H.setAttribute("aria-label","\uD30C\uC77C \uCCA8\uBD80 (\uC774\uBBF8\uC9C0/PDF)"),H.type="button";let A=document.createElement("input");A.type="file",A.accept=Ge,A.multiple=!0,A.style.display="none",H.addEventListener("click",()=>A.click()),A.addEventListener("change",()=>{let e=A.files?Array.from(A.files):[];for(let r of e)oe(r);A.value=""});let w=document.createElement("textarea");w.placeholder="\uBA54\uC2DC\uC9C0\uB97C \uC785\uB825\uD558\uC138\uC694\u2026 (Enter \uC804\uC1A1, Shift+Enter \uC904\uBC14\uAFC8)",w.rows=1;let C=u("button","mic-btn","\u{1F3A4}");C.type="button",C.setAttribute("aria-label","\uC74C\uC131 \uC785\uB825 \uC2DC\uC791"),C.setAttribute("aria-pressed","false"),C.hidden=!0;let D=u("button","send-btn","\uC804\uC1A1");L.appendChild(H),L.appendChild(A),L.appendChild(w),L.appendChild(C),L.appendChild(D),d.appendChild(L);let _=u("div","recording-overlay hidden");_.setAttribute("role","status"),_.setAttribute("aria-live","polite");let ye=u("span","rec-wave","\u2581\u2583\u2585\u2587\u2585\u2583\u2581"),ne=u("span","rec-timer","0:00"),B=u("button","rec-cancel","\uCDE8\uC18C");B.type="button",B.setAttribute("aria-label","\uB179\uC74C \uCDE8\uC18C");let F=u("button","rec-stop","\u25A0 \uC815\uC9C0");F.type="button",F.setAttribute("aria-label","\uB179\uC74C \uC815\uC9C0 \uD6C4 \uBCC0\uD658"),_.appendChild(ye),_.appendChild(ne),_.appendChild(B),_.appendChild(F),d.appendChild(_);let U=0,M=null;d.addEventListener("dragenter",e=>{J(e)&&(e.preventDefault(),U+=1,M||(M=u("div","drop-overlay","\uD30C\uC77C\uC744 \uB193\uC544 \uCCA8\uBD80\uD558\uC138\uC694"),d.appendChild(M)))}),d.addEventListener("dragover",e=>{J(e)&&e.preventDefault()}),d.addEventListener("dragleave",e=>{J(e)&&(U-=1,U<=0&&(U=0,M?.remove(),M=null))}),d.addEventListener("drop",e=>{if(!J(e))return;e.preventDefault(),U=0,M?.remove(),M=null;let r=e.dataTransfer?Array.from(e.dataTransfer.files):[];for(let a of r)oe(a)});function J(e){return!!e.dataTransfer&&Array.from(e.dataTransfer.types??[]).includes("Files")}function j(e){o.open=e,e?d.classList.remove("hidden"):d.classList.add("hidden")}function E(e,r){let a=u("div",`msg ${e}`,r);return S.appendChild(a),S.scrollTop=S.scrollHeight,a}function we(e){if(!e?.length)return;let r=u("div","citations");e.forEach((a,l)=>{let x=u("button","citation",`[${a.index??l+1}] ${a.document_name??a.source_id}`);x.title=a.content_preview,x.addEventListener("click",()=>ke(a)),r.appendChild(x)}),S.appendChild(r),S.scrollTop=S.scrollHeight}async function ke(e){let r=d.querySelector(".citation-preview");r&&r.remove();let a=u("div","citation-preview"),l=u("button","close","\xD7");l.addEventListener("click",()=>a.remove()),a.appendChild(l),a.appendChild(u("h4","",e.document_name??e.source_id)),a.appendChild(u("div","meta",`score ${e.score.toFixed(2)}${e.page_number?` \xB7 page ${e.page_number}`:""}`));let x=u("div","content",e.content_preview);if(a.appendChild(x),d.appendChild(a),e.chunk_id)try{let p=await ue.fetchChunk(n.baseUrl,e.source_id,e.chunk_id);typeof p.content=="string"&&(x.textContent=p.content)}catch{}}function Ee(){return o.currentAssistantDiv||(o.currentAssistantDiv=E("assistant",""),o.currentAssistantText=""),o.currentAssistantDiv}function le(){o.currentAssistantDiv=null,o.currentAssistantText=""}let $=new Q(n.authResolver,n.on?.onTokenExpiring),pe=new Z($),ue=new ee($),me=new Y($);function Te(){return`att-${Date.now()}-${Math.random().toString(36).slice(2,8)}`}function Se(e){let r=e.type.startsWith("image/"),a=e.type==="application/pdf";if(!r&&!a)return"\uC774\uBBF8\uC9C0(PNG/JPEG/GIF/WEBP) \uB610\uB294 PDF \uB9CC \uCCA8\uBD80 \uAC00\uB2A5\uD569\uB2C8\uB2E4";let l=a?Xe:Ve;return e.size>l?`\uD30C\uC77C\uC774 \uB108\uBB34 \uD07D\uB2C8\uB2E4 (\uCD5C\uB300 ${Math.round(l/1024/1024)}MB)`:null}function q(){re.innerHTML="";for(let e of o.attachments){let r=u("div",`chip ${e.status}`);if(e.previewDataUrl){let p=document.createElement("img");p.className="chip-thumb",p.src=e.previewDataUrl,p.alt=e.file.name,r.appendChild(p)}else{let p=u("span","chip-thumb pdf","PDF");r.appendChild(p)}let a=u("span","chip-label"),l=e.serverMeta?.pages,x=l?` \xB7 ${l}p`:"";if(a.textContent=e.status==="error"?`${e.file.name} \u2014 ${e.error??"\uC2E4\uD328"}`:`${e.file.name}${x}`,a.title=a.textContent??"",r.appendChild(a),e.status==="uploading")r.appendChild(u("span","chip-spinner"));else{let p=u("button","chip-remove","\xD7");p.type="button",p.setAttribute("aria-label","\uCCA8\uBD80 \uC81C\uAC70"),p.addEventListener("click",()=>{Ce(e.localId)}),r.appendChild(p)}re.appendChild(r)}O()}async function Ae(e){if(e.type.startsWith("image/"))return new Promise(r=>{let a=new FileReader;a.onload=()=>r(typeof a.result=="string"?a.result:void 0),a.onerror=()=>r(void 0),a.readAsDataURL(e)})}async function oe(e){let r=Se(e);if(r){n.on?.onError?.(new Error(r)),E("error",r);return}let a={localId:Te(),file:e,status:"uploading",previewDataUrl:await Ae(e)};o.attachments.push(a),q();try{let l=await me.upload({baseUrl:n.baseUrl,sessionId:o.sessionId,file:e});a.serverMeta=l,a.status="ready"}catch(l){a.status="error",a.error=l.message,n.on?.onError?.(l)}finally{q()}}async function Ce(e){let r=o.attachments.findIndex(l=>l.localId===e);if(r<0)return;let a=o.attachments[r];if(o.attachments.splice(r,1),q(),a.serverMeta)try{await me.delete({baseUrl:n.baseUrl,sessionId:o.sessionId,attachmentId:a.serverMeta.attachment_id})}catch{}}let _e=new W($),k=null,z=[],K=null,fe=0,G=null,V=null,I="idle",Re=6e4;function be(){return typeof window<"u"&&!!window.isSecureContext&&!!navigator.mediaDevices?.getUserMedia&&typeof MediaRecorder<"u"}function Me(){let e=["audio/webm;codecs=opus","audio/webm","audio/mp4"];for(let r of e)try{if(MediaRecorder.isTypeSupported(r))return r}catch{}}function R(e){I=e,C.setAttribute("aria-pressed",e==="recording"?"true":"false"),C.setAttribute("aria-label",e==="recording"?"\uB179\uC74C \uC815\uC9C0":"\uC74C\uC131 \uC785\uB825 \uC2DC\uC791"),C.textContent=e==="recording"?"\u23F9":"\u{1F3A4}",e==="recording"?_.classList.remove("hidden"):_.classList.add("hidden")}function $e(e){let r=Math.floor(e/1e3),a=Math.floor(r/60),l=r%60;return`${a}:${l.toString().padStart(2,"0")}`}function he(){G&&(clearInterval(G),G=null),V&&(clearTimeout(V),V=null)}function ae(){K?.getTracks().forEach(e=>e.stop()),K=null}async function Ie(){if(I!=="idle")return;if(!be()){E("error","\uC774 \uBE0C\uB77C\uC6B0\uC800\xB7\uD658\uACBD\uC5D0\uC11C\uB294 \uC74C\uC131 \uC785\uB825\uC744 \uC0AC\uC6A9\uD560 \uC218 \uC5C6\uC2B5\uB2C8\uB2E4 (HTTPS \uD544\uC218)");return}R("requesting-permission");try{K=await navigator.mediaDevices.getUserMedia({audio:!0})}catch(r){R("error"),E("error","\uB9C8\uC774\uD06C \uAD8C\uD55C\uC774 \uD544\uC694\uD569\uB2C8\uB2E4. \uBE0C\uB77C\uC6B0\uC800 \uC8FC\uC18C\uCC3D\uC5D0\uC11C \u{1F512} \uC544\uC774\uCF58\uC744 \uB20C\uB7EC \uD5C8\uC6A9\uD574\uC8FC\uC138\uC694"),n.on?.onError?.(r),R("idle");return}let e=Me();try{k=new MediaRecorder(K,e?{mimeType:e}:void 0)}catch(r){ae(),R("idle"),E("error","\uC774 \uBE0C\uB77C\uC6B0\uC800\uC758 MediaRecorder \uD3EC\uB9F7\uC774 \uC11C\uBC84\uC640 \uD638\uD658\uB418\uC9C0 \uC54A\uC2B5\uB2C8\uB2E4"),n.on?.onError?.(r);return}z=[],k.ondataavailable=r=>{r.data.size>0&&z.push(r.data)},k.onstop=()=>{Le()},k.onerror=r=>{n.on?.onError?.(new Error("recorder error: "+r.type))},k.start(),fe=Date.now(),R("recording"),ne.textContent="0:00",G=setInterval(()=>{let r=Date.now()-fe;ne.textContent=$e(r)},250),V=setTimeout(()=>{I==="recording"&&se()},Re)}function se(){if(I==="recording"){he();try{k?.stop()}catch{}R("uploading")}}function ie(){if(he(),k&&k.state!=="inactive"){k.onstop=null;try{k.stop()}catch{}}ae(),k=null,z=[],R("idle")}async function Le(){let e=z;if(z=[],ae(),!e.length){R("idle");return}let r=k?.mimeType||"audio/webm";k=null;let a=new Blob(e,{type:r});try{let l=await _e.transcribe({baseUrl:n.baseUrl,sessionId:o.sessionId,blob:a});Oe(w,l.text),w.focus(),O()}catch(l){let x=l instanceof T?l:new Error(String(l));E("error",De(x)),n.on?.onError?.(x)}finally{R("idle")}}function De(e){if(e instanceof T)switch(e.code){case"STT_RATE_LIMITED":return"\uC74C\uC131 \uC785\uB825\uC744 \uB108\uBB34 \uC790\uC8FC \uC0AC\uC6A9\uD588\uC2B5\uB2C8\uB2E4. \uC7A0\uC2DC \uD6C4 \uB2E4\uC2DC \uC2DC\uB3C4\uD574\uC8FC\uC138\uC694";case"STT_FILE_TOO_LARGE":return"\uB179\uC74C \uD30C\uC77C\uC774 \uB108\uBB34 \uD07D\uB2C8\uB2E4";case"STT_FILE_TOO_LONG":return"\uB179\uC74C \uC2DC\uAC04\uC774 \uB108\uBB34 \uAE41\uB2C8\uB2E4";case"STT_INVALID_MIME":return"\uC774 \uC624\uB514\uC624 \uD3EC\uB9F7\uC740 \uC11C\uBC84\uC5D0\uC11C \uC9C0\uC6D0\uD558\uC9C0 \uC54A\uC2B5\uB2C8\uB2E4";case"STT_PROVIDER_UNAVAILABLE":return"\uC74C\uC131 \uC778\uC2DD \uC11C\uBE44\uC2A4\uB97C \uC0AC\uC6A9\uD560 \uC218 \uC5C6\uC2B5\uB2C8\uB2E4 (OpenAI \uC5F0\uACB0 \uD655\uC778 \uD544\uC694)";case"STT_TIMEOUT":return"\uC74C\uC131 \uC778\uC2DD \uC751\uB2F5\uC774 \uC9C0\uC5F0\uB429\uB2C8\uB2E4. \uB2E4\uC2DC \uC2DC\uB3C4\uD574\uC8FC\uC138\uC694";case"STT_NETWORK":return"\uB124\uD2B8\uC6CC\uD06C \uC624\uB958\uB85C \uC74C\uC131\uC744 \uC804\uC1A1\uD558\uC9C0 \uBABB\uD588\uC2B5\uB2C8\uB2E4"}return"\uC74C\uC131 \uC778\uC2DD\uC5D0 \uC2E4\uD328\uD588\uC2B5\uB2C8\uB2E4: "+e.message}function Oe(e,r){let a=e.selectionStart??e.value.length,l=e.selectionEnd??e.value.length,x=e.value.slice(0,a),p=e.value.slice(l),y=(x.length>0&&!/\s$/.test(x)?" ":"")+r;e.value=x+y+p;let X=(x+y).length;e.setSelectionRange(X,X)}C.addEventListener("click",()=>{I==="idle"?Ie():I==="recording"&&se()}),F.addEventListener("click",()=>se()),B.addEventListener("click",()=>ie()),d.addEventListener("keydown",e=>{e.key==="Escape"&&I==="recording"&&(e.stopPropagation(),ie())}),(async()=>{try{await $.ensureLoaded(),be()&&$.hasScope("chat:stt")&&(C.hidden=!1)}catch{}})();function O(){let e=o.attachments.some(l=>l.status==="uploading"),r=w.value.trim().length>0,a=o.attachments.some(l=>l.status==="ready");e||!r&&!a?D.setAttribute("disabled","true"):D.removeAttribute("disabled")}async function de(e){let r=e.trim(),a=o.attachments.filter(p=>p.status==="ready"&&p.serverMeta);if(!r&&a.length===0)return;let l=[r,...a.map(p=>`\u{1F4CE} ${p.file.name}`)].filter(p=>p.length>0).join(`
`);E("user",l),le(),D.setAttribute("disabled","true");let x=a.map(p=>({attachmentId:p.serverMeta.attachment_id,mediaType:p.serverMeta.media_type}));o.attachments=[],q();try{await pe.sendMessage({baseUrl:n.baseUrl,sessionId:o.sessionId,text:r,ragSourceId:o.ragSourceId,context:n.contextProvider?.(),attachments:x},p=>{switch(n.on?.onMessage?.(p),p.type){case"delta":{let h=Ee();o.currentAssistantText+=p.text??"",h.textContent=o.currentAssistantText,S.scrollTop=S.scrollHeight;break}case"thinking":E("thinking",`\u{1F4AD} ${p.text??""}`);break;case"tool_use_start":E("tool",`\u{1F6E0} ${p.tool?.name??"tool"} \uC2E4\uD589\u2026`);break;case"tool_result":p.toolResult?.is_error&&E("error",`\uB3C4\uAD6C \uC5D0\uB7EC: ${p.toolResult.output.slice(0,200)}`);break;case"done":p.done?.citations?.length&&we(p.done.citations),le();break;case"error":E("error",p.error??"unknown error");break}})}catch(p){let h=p;h?.name!=="AbortError"&&(E("error",h.message),n.on?.onError?.(h))}finally{D.removeAttribute("disabled")}}D.addEventListener("click",()=>{de(w.value),w.value="",O()}),w.addEventListener("keydown",e=>{e.isComposing||e.keyCode===229||e.key==="Enter"&&!e.shiftKey&&(e.preventDefault(),de(w.value),w.value="",O())}),w.addEventListener("input",O),w.addEventListener("paste",e=>{let r=e.clipboardData?.items;if(r){for(let a of r)if(a.kind==="file"){let l=a.getAsFile();l&&oe(l)}}}),O();async function We(e){let r=u("div","workflow-panel"),a=u("div","title",`\uC6CC\uD06C\uD50C\uB85C\uC6B0 ${e.slice(0,8)}\u2026`);r.appendChild(a);let l=u("div","steps");r.appendChild(l),S.appendChild(r);let x=new Map;return o.workflowRuns.set(e,{stepEl:r,steps:x}),await ue.subscribe(n.baseUrl,e,{onSnapshot:h=>{a.textContent=`\uC6CC\uD06C\uD50C\uB85C\uC6B0 ${h.workflow_id??e.slice(0,8)} \u2014 ${h.status}`},onStep:h=>{n.on?.onWorkflowStep?.(h);let y=x.get(h.step_id);y||(y=u("div","workflow-step"),x.set(h.step_id,y),l.appendChild(y));let X=h.status==="running"?"running":h.status==="completed"?"completed":"failed";y.innerHTML="";let Pe=u("span",`dot ${X}`),Ue=u("span","label",`${h.step_id}${h.duration_ms?` \xB7 ${h.duration_ms}ms`:""}`);if(y.appendChild(Pe),y.appendChild(Ue),h.sub_workflow_id){let ge=u("span","sub",` \u2192 ${h.sub_workflow_id}`);ge.style.color="var(--aimbase-muted)",y.appendChild(ge)}},onApproval:h=>{n.on?.onApprovalRequired?.(h);let y=u("div","workflow-step");y.innerHTML=`\u23F8 \uC2B9\uC778 \uB300\uAE30: <b>${h.step_id}</b> \u2014 ${h.reason??h.policy_id}`,y.style.color="#b45309",l.appendChild(y)},onDone:h=>{a.textContent=`\uC6CC\uD06C\uD50C\uB85C\uC6B0 ${e.slice(0,8)}\u2026 \u2014 ${h.status} (${h.duration_ms}ms)`,o.workflowRuns.delete(e)},onError:h=>{n.on?.onError?.(h)}})}return{open:()=>j(!0),close:()=>j(!1),sendMessage:de,abort:()=>pe.abort(n.baseUrl,o.sessionId),subscribeWorkflow:e=>{let r=null;return We(e).then(a=>r=a),()=>r?.()},destroy:()=>{ie(),$.destroy(),i.remove()}}}function Ze(n){return n==="bubble"?"floating":n==="panel"?"side":"inline"}var N=class extends HTMLElement{constructor(){super(...arguments);this.handle=null;this.authResolver=null;this.contextProvider=null;this.ragSourceId=null}static get observedAttributes(){return["base-url","token-endpoint","display","theme-mode","rag-source-id","session-id"]}connectedCallback(){let s=this.getAttribute("base-url");if(!s){console.error("[aimbase-chat] base-url attribute is required");return}let i=this.getAttribute("token-endpoint"),c=this.authResolver??(i?async()=>{let o=await fetch(i,{method:"POST",credentials:"include"});if(!o.ok)throw new Error(`token endpoint ${o.status}`);let m=await o.json();return m.data??m}:null);if(!c){console.error("[aimbase-chat] token-endpoint attribute or authResolver property is required");return}let v=this.getAttribute("display")??"inline",b=this.getAttribute("theme-mode"),f={baseUrl:s,authResolver:c,display:v,sessionId:this.getAttribute("session-id")??void 0,ragSourceId:this.ragSourceId??this.getAttribute("rag-source-id")??void 0,target:v==="inline"?this:void 0,theme:b?{mode:b}:void 0,contextProvider:this.contextProvider??void 0};this.handle=P(f)}disconnectedCallback(){this.handle?.destroy(),this.handle=null}subscribeWorkflow(s){return this.handle?.subscribeWorkflow(s)??(()=>{})}open(){this.handle?.open()}close(){this.handle?.close()}};function te(){typeof customElements>"u"||customElements.get("aimbase-chat")||customElements.define("aimbase-chat",N)}if(typeof window<"u")try{te()}catch{}function Qe(n){return P(n)}return Je(et);})();
//# sourceMappingURL=aimbase-chat.umd.global.js.map