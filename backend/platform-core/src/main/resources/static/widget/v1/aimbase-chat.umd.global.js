"use strict";var AimbaseChat=(()=>{var J=Object.defineProperty;var ce=Object.getOwnPropertyDescriptor;var pe=Object.getOwnPropertyNames;var ue=Object.prototype.hasOwnProperty;var me=(o,t)=>{for(var s in t)J(o,s,{get:t[s],enumerable:!0})},he=(o,t,s,i)=>{if(t&&typeof t=="object"||typeof t=="function")for(let p of pe(t))!ue.call(o,p)&&p!==s&&J(o,p,{get:()=>t[p],enumerable:!(i=ce(t,p))||i.enumerable});return o};var fe=o=>he(J({},"__esModule",{value:!0}),o);var Ee={};me(Ee,{AimbaseChatElement:()=>I,createWidget:()=>S,defineAimbaseChat:()=>B,init:()=>ke});var U=class{constructor(t){this.tokens=t}async upload(t,s){let i=await this.tokens.getToken(),p=new FormData;return p.append("session_id",t.sessionId),p.append("file",t.file,t.file.name),new Promise((b,h)=>{let u=new XMLHttpRequest;u.open("POST",`${t.baseUrl}/api/v1/chat/attachments`),u.setRequestHeader("Authorization",`Bearer ${i}`),s&&u.upload&&u.upload.addEventListener("progress",r=>{if(r.lengthComputable){let g=Math.round(r.loaded/r.total*100);s(g)}}),u.onload=()=>{if(u.status>=200&&u.status<300)try{let r=JSON.parse(u.responseText),g=r.data??r;b(g)}catch(r){h(new Error(`invalid response JSON: ${r.message}`))}else{let r=be(u.responseText)??`status ${u.status}`;h(new Error(r))}},u.onerror=()=>h(new Error("network error")),u.send(p)})}async delete(t){let s=await this.tokens.getToken(),i=`${t.baseUrl}/api/v1/chat/attachments/${encodeURIComponent(t.attachmentId)}?session_id=${encodeURIComponent(t.sessionId)}`,p=await fetch(i,{method:"DELETE",headers:{Authorization:`Bearer ${s}`}});if(!p.ok&&p.status!==404){let b=await p.text().catch(()=>"");throw new Error(`delete attachment ${p.status}: ${b.slice(0,200)}`)}}};function be(o){if(!o)return null;try{let t=JSON.parse(o);return typeof t.error=="string"?t.error:typeof t.message=="string"?t.message:null}catch{return o.slice(0,200)}}async function*Y(o,t){if(!o.body)throw new Error("SSE response has no body");let s=o.body.getReader(),i=new TextDecoder("utf-8"),p="",b="message";try{for(;!t?.aborted;){let{value:h,done:u}=await s.read();if(u)break;p+=i.decode(h,{stream:!0});let r;for(;(r=p.indexOf(`

`))!==-1;){let g=p.slice(0,r);p=p.slice(r+2);let l="";for(let E of g.split(`
`))E.startsWith("event:")?b=E.slice(6).trim()||"message":E.startsWith("data:")?l+=E.slice(5).replace(/^ /,""):E.startsWith(":");l&&(yield{name:b,data:l},b="message")}}}finally{try{s.releaseLock()}catch{}}}var z=class{constructor(t){this.tokens=t;this.currentAbort=null}async sendMessage(t,s){this.currentAbort?.abort();let i=new AbortController;this.currentAbort=i;let p=await this.tokens.getToken(),b=[];for(let g of t.attachments??[])b.push({type:g.mediaType==="application/pdf"?"document":"image",attachment_id:g.attachmentId});t.text&&t.text.length>0&&b.push({type:"text",text:t.text});let h=[];t.context&&Object.keys(t.context).length>0&&h.push({role:"system",content:[{type:"text",text:`# \uD604\uC7AC \uD654\uBA74 \uCEE8\uD14D\uC2A4\uD2B8
${JSON.stringify(t.context,null,2)}`}]}),h.push({role:"user",content:b});let u={model:t.model??"auto",session_id:t.sessionId,stream:!0,messages:h};t.ragSourceId&&(u.rag_source_id=t.ragSourceId),t.connectionId&&(u.connection_id=t.connectionId);let r=await fetch(`${t.baseUrl}/api/v1/chat/completions`,{method:"POST",headers:{Authorization:`Bearer ${p}`,"Content-Type":"application/json",Accept:"text/event-stream"},body:JSON.stringify(u),signal:i.signal});if(!r.ok){let g=await r.text().catch(()=>"");throw new Error(`chat/completions ${r.status}: ${g.slice(0,200)}`)}for await(let g of Y(r,i.signal))try{let l=JSON.parse(g.data);switch(g.name){case"delta":s({type:"delta",text:l.delta??""});break;case"thinking":s({type:"thinking",text:l.delta??""});break;case"tool_use_start":s({type:"tool_use_start",tool:{id:l.id,name:l.name,input:l.input}});break;case"tool_result":s({type:"tool_result",toolResult:{tool_use_id:l.tool_use_id,output:l.output,is_error:!!l.is_error}});break;case"done":s({type:"done",done:{rag_used:!!l.rag_used,citations:Array.isArray(l.citations)?l.citations:[]}});break;default:break}}catch(l){s({type:"error",error:`parse error: ${l.message}`})}}async abort(t,s){this.currentAbort?.abort(),this.currentAbort=null;let i=await this.tokens.getToken();try{await fetch(`${t}/api/v1/chat/${encodeURIComponent(s)}/abort`,{method:"POST",headers:{Authorization:`Bearer ${i}`}})}catch{}}};var Q=`
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
`;var O=class{constructor(t,s){this.resolver=t;this.token=null;this.expiresAtMs=0;this.refreshTimer=null;this.onExpiring=s}async getToken(){return(!this.token||Date.now()>=this.expiresAtMs-3e4)&&await this.refresh(),this.token.token}async refresh(){let t=await this.resolver();this.token=t,this.expiresAtMs=typeof t.expires_at=="number"?t.expires_at:new Date(t.expires_at).getTime(),this.scheduleRefresh(t.refresh_after)}scheduleRefresh(t){this.refreshTimer&&clearTimeout(this.refreshTimer);let s=Math.max(1e4,t*1e3);this.refreshTimer=setTimeout(()=>{try{this.onExpiring?.()}catch{}this.refresh().catch(()=>{})},s)}destroy(){this.refreshTimer&&clearTimeout(this.refreshTimer),this.refreshTimer=null,this.token=null}};var H=class{constructor(t){this.tokens=t}async subscribe(t,s,i){let p=await this.tokens.getToken(),b=`${t}/api/v1/workflows/runs/${encodeURIComponent(s)}/subscribe?access_token=${encodeURIComponent(p)}`,h=new EventSource(b);return h.addEventListener("workflow.snapshot",u=>{try{i.onSnapshot?.(JSON.parse(u.data))}catch(r){i.onError?.(r)}}),h.addEventListener("workflow.step",u=>{try{i.onStep?.(JSON.parse(u.data))}catch(r){i.onError?.(r)}}),h.addEventListener("workflow.approval",u=>{try{i.onApproval?.(JSON.parse(u.data))}catch(r){i.onError?.(r)}}),h.addEventListener("workflow.done",u=>{try{i.onDone?.(JSON.parse(u.data))}catch(r){i.onError?.(r)}finally{h.close()}}),h.onerror=()=>{h.readyState===EventSource.CLOSED&&i.onError?.(new Error("workflow SSE closed"))},()=>h.close()}async fetchChunk(t,s,i){let p=await this.tokens.getToken(),b=await fetch(`${t}/api/v1/knowledge-sources/${encodeURIComponent(s)}/chunks/${encodeURIComponent(i)}`,{headers:{Authorization:`Bearer ${p}`}});if(!b.ok)throw new Error(`chunks ${b.status}`);return(await b.json()).data??{}}};var ge="image/png,image/jpeg,image/gif,image/webp,application/pdf",ve=10*1024*1024,xe=32*1024*1024;function c(o,t,s){let i=document.createElement(o);return t&&(i.className=t),s&&(i.textContent=s),i}function ye(){try{return self.crypto.randomUUID()}catch{return`sess-${Date.now()}-${Math.random().toString(36).slice(2,10)}`}}function S(o){let t=o.display??"bubble",s=o.theme?.mode??"auto",i=document.createElement("div");i.setAttribute("data-aimbase-widget","true"),s==="dark"&&i.setAttribute("data-theme","dark"),s==="light"&&i.setAttribute("data-theme","light");let p=typeof o.target=="string"?document.querySelector(o.target):o.target??null;if((t==="inline"||t==="panel")&&!p)throw new Error(`display='${t}' requires options.target`);(p??document.body).appendChild(i);let b=i.attachShadow({mode:"open"}),h=document.createElement("style");if(h.textContent=Q,b.appendChild(h),o.theme?.cssVars){let e=Object.entries(o.theme.cssVars).map(([n,m])=>`${n}: ${m};`).join(" "),a=document.createElement("style");a.textContent=`:host { ${e} }`,b.appendChild(a)}let u=c("div","root");b.appendChild(u);let r={open:t!=="bubble",sessionId:o.sessionId??ye(),ragSourceId:o.ragSourceId,currentAssistantDiv:null,currentAssistantText:"",workflowRuns:new Map,attachments:[]},g=null;t==="bubble"&&(g=c("button","bubble-btn","\u{1F4AC}"),g.setAttribute("aria-label","Open Aimbase chat"),g.addEventListener("click",()=>L(!0)),u.appendChild(g));let l=c("div",`panel ${we(t)} ${r.open?"":"hidden"}`);u.appendChild(l);let E=c("div","header");if(E.appendChild(c("div","title","Aimbase Chat")),t==="bubble"||t==="panel"){let e=c("button","close","\xD7");e.addEventListener("click",()=>L(!1)),E.appendChild(e)}l.appendChild(E);let w=c("div","messages");l.appendChild(w);let F=c("div","attachments");l.appendChild(F);let _=c("div","composer"),D=c("button","attach-btn","\u{1F4CE}");D.setAttribute("aria-label","\uD30C\uC77C \uCCA8\uBD80 (\uC774\uBBF8\uC9C0/PDF)"),D.type="button";let k=document.createElement("input");k.type="file",k.accept=ge,k.multiple=!0,k.style.display="none",D.addEventListener("click",()=>k.click()),k.addEventListener("change",()=>{let e=k.files?Array.from(k.files):[];for(let a of e)N(a);k.value=""});let y=document.createElement("textarea");y.placeholder="\uBA54\uC2DC\uC9C0\uB97C \uC785\uB825\uD558\uC138\uC694\u2026 (Enter \uC804\uC1A1, Shift+Enter \uC904\uBC14\uAFC8)",y.rows=1;let T=c("button","send-btn","\uC804\uC1A1");_.appendChild(D),_.appendChild(k),_.appendChild(y),_.appendChild(T),l.appendChild(_);let $=0,C=null;l.addEventListener("dragenter",e=>{R(e)&&(e.preventDefault(),$+=1,C||(C=c("div","drop-overlay","\uD30C\uC77C\uC744 \uB193\uC544 \uCCA8\uBD80\uD558\uC138\uC694"),l.appendChild(C)))}),l.addEventListener("dragover",e=>{R(e)&&e.preventDefault()}),l.addEventListener("dragleave",e=>{R(e)&&($-=1,$<=0&&($=0,C?.remove(),C=null))}),l.addEventListener("drop",e=>{if(!R(e))return;e.preventDefault(),$=0,C?.remove(),C=null;let a=e.dataTransfer?Array.from(e.dataTransfer.files):[];for(let n of a)N(n)});function R(e){return!!e.dataTransfer&&Array.from(e.dataTransfer.types??[]).includes("Files")}function L(e){r.open=e,e?l.classList.remove("hidden"):l.classList.add("hidden")}function A(e,a){let n=c("div",`msg ${e}`,a);return w.appendChild(n),w.scrollTop=w.scrollHeight,n}function Z(e){if(!e?.length)return;let a=c("div","citations");e.forEach((n,m)=>{let v=c("button","citation",`[${n.index??m+1}] ${n.document_name??n.source_id}`);v.title=n.content_preview,v.addEventListener("click",()=>ee(n)),a.appendChild(v)}),w.appendChild(a),w.scrollTop=w.scrollHeight}async function ee(e){let a=l.querySelector(".citation-preview");a&&a.remove();let n=c("div","citation-preview"),m=c("button","close","\xD7");m.addEventListener("click",()=>n.remove()),n.appendChild(m),n.appendChild(c("h4","",e.document_name??e.source_id)),n.appendChild(c("div","meta",`score ${e.score.toFixed(2)}${e.page_number?` \xB7 page ${e.page_number}`:""}`));let v=c("div","content",e.content_preview);if(n.appendChild(v),l.appendChild(n),e.chunk_id)try{let d=await K.fetchChunk(o.baseUrl,e.source_id,e.chunk_id);typeof d.content=="string"&&(v.textContent=d.content)}catch{}}function te(){return r.currentAssistantDiv||(r.currentAssistantDiv=A("assistant",""),r.currentAssistantText=""),r.currentAssistantDiv}function q(){r.currentAssistantDiv=null,r.currentAssistantText=""}let W=new O(o.authResolver,o.on?.onTokenExpiring),G=new z(W),K=new H(W),X=new U(W);function re(){return`att-${Date.now()}-${Math.random().toString(36).slice(2,8)}`}function ne(e){let a=e.type.startsWith("image/"),n=e.type==="application/pdf";if(!a&&!n)return"\uC774\uBBF8\uC9C0(PNG/JPEG/GIF/WEBP) \uB610\uB294 PDF \uB9CC \uCCA8\uBD80 \uAC00\uB2A5\uD569\uB2C8\uB2E4";let m=n?xe:ve;return e.size>m?`\uD30C\uC77C\uC774 \uB108\uBB34 \uD07D\uB2C8\uB2E4 (\uCD5C\uB300 ${Math.round(m/1024/1024)}MB)`:null}function P(){F.innerHTML="";for(let e of r.attachments){let a=c("div",`chip ${e.status}`);if(e.previewDataUrl){let d=document.createElement("img");d.className="chip-thumb",d.src=e.previewDataUrl,d.alt=e.file.name,a.appendChild(d)}else{let d=c("span","chip-thumb pdf","PDF");a.appendChild(d)}let n=c("span","chip-label"),m=e.serverMeta?.pages,v=m?` \xB7 ${m}p`:"";if(n.textContent=e.status==="error"?`${e.file.name} \u2014 ${e.error??"\uC2E4\uD328"}`:`${e.file.name}${v}`,n.title=n.textContent??"",a.appendChild(n),e.status==="uploading")a.appendChild(c("span","chip-spinner"));else{let d=c("button","chip-remove","\xD7");d.type="button",d.setAttribute("aria-label","\uCCA8\uBD80 \uC81C\uAC70"),d.addEventListener("click",()=>{ae(e.localId)}),a.appendChild(d)}F.appendChild(a)}M()}async function oe(e){if(e.type.startsWith("image/"))return new Promise(a=>{let n=new FileReader;n.onload=()=>a(typeof n.result=="string"?n.result:void 0),n.onerror=()=>a(void 0),n.readAsDataURL(e)})}async function N(e){let a=ne(e);if(a){o.on?.onError?.(new Error(a)),A("error",a);return}let n={localId:re(),file:e,status:"uploading",previewDataUrl:await oe(e)};r.attachments.push(n),P();try{let m=await X.upload({baseUrl:o.baseUrl,sessionId:r.sessionId,file:e});n.serverMeta=m,n.status="ready"}catch(m){n.status="error",n.error=m.message,o.on?.onError?.(m)}finally{P()}}async function ae(e){let a=r.attachments.findIndex(m=>m.localId===e);if(a<0)return;let n=r.attachments[a];if(r.attachments.splice(a,1),P(),n.serverMeta)try{await X.delete({baseUrl:o.baseUrl,sessionId:r.sessionId,attachmentId:n.serverMeta.attachment_id})}catch{}}function M(){let e=r.attachments.some(m=>m.status==="uploading"),a=y.value.trim().length>0,n=r.attachments.some(m=>m.status==="ready");e||!a&&!n?T.setAttribute("disabled","true"):T.removeAttribute("disabled")}async function j(e){let a=e.trim(),n=r.attachments.filter(d=>d.status==="ready"&&d.serverMeta);if(!a&&n.length===0)return;let m=[a,...n.map(d=>`\u{1F4CE} ${d.file.name}`)].filter(d=>d.length>0).join(`
`);A("user",m),q(),T.setAttribute("disabled","true");let v=n.map(d=>({attachmentId:d.serverMeta.attachment_id,mediaType:d.serverMeta.media_type}));r.attachments=[],P();try{await G.sendMessage({baseUrl:o.baseUrl,sessionId:r.sessionId,text:a,ragSourceId:r.ragSourceId,context:o.contextProvider?.(),attachments:v},d=>{switch(o.on?.onMessage?.(d),d.type){case"delta":{let f=te();r.currentAssistantText+=d.text??"",f.textContent=r.currentAssistantText,w.scrollTop=w.scrollHeight;break}case"thinking":A("thinking",`\u{1F4AD} ${d.text??""}`);break;case"tool_use_start":A("tool",`\u{1F6E0} ${d.tool?.name??"tool"} \uC2E4\uD589\u2026`);break;case"tool_result":d.toolResult?.is_error&&A("error",`\uB3C4\uAD6C \uC5D0\uB7EC: ${d.toolResult.output.slice(0,200)}`);break;case"done":d.done?.citations?.length&&Z(d.done.citations),q();break;case"error":A("error",d.error??"unknown error");break}})}catch(d){A("error",d.message),o.on?.onError?.(d)}finally{T.removeAttribute("disabled")}}T.addEventListener("click",()=>{j(y.value),y.value="",M()}),y.addEventListener("keydown",e=>{e.key==="Enter"&&!e.shiftKey&&(e.preventDefault(),j(y.value),y.value="",M())}),y.addEventListener("input",M),y.addEventListener("paste",e=>{let a=e.clipboardData?.items;if(a){for(let n of a)if(n.kind==="file"){let m=n.getAsFile();m&&N(m)}}}),M();async function se(e){let a=c("div","workflow-panel"),n=c("div","title",`\uC6CC\uD06C\uD50C\uB85C\uC6B0 ${e.slice(0,8)}\u2026`);a.appendChild(n);let m=c("div","steps");a.appendChild(m),w.appendChild(a);let v=new Map;return r.workflowRuns.set(e,{stepEl:a,steps:v}),await K.subscribe(o.baseUrl,e,{onSnapshot:f=>{n.textContent=`\uC6CC\uD06C\uD50C\uB85C\uC6B0 ${f.workflow_id??e.slice(0,8)} \u2014 ${f.status}`},onStep:f=>{o.on?.onWorkflowStep?.(f);let x=v.get(f.step_id);x||(x=c("div","workflow-step"),v.set(f.step_id,x),m.appendChild(x));let ie=f.status==="running"?"running":f.status==="completed"?"completed":"failed";x.innerHTML="";let de=c("span",`dot ${ie}`),le=c("span","label",`${f.step_id}${f.duration_ms?` \xB7 ${f.duration_ms}ms`:""}`);if(x.appendChild(de),x.appendChild(le),f.sub_workflow_id){let V=c("span","sub",` \u2192 ${f.sub_workflow_id}`);V.style.color="var(--aimbase-muted)",x.appendChild(V)}},onApproval:f=>{o.on?.onApprovalRequired?.(f);let x=c("div","workflow-step");x.innerHTML=`\u23F8 \uC2B9\uC778 \uB300\uAE30: <b>${f.step_id}</b> \u2014 ${f.reason??f.policy_id}`,x.style.color="#b45309",m.appendChild(x)},onDone:f=>{n.textContent=`\uC6CC\uD06C\uD50C\uB85C\uC6B0 ${e.slice(0,8)}\u2026 \u2014 ${f.status} (${f.duration_ms}ms)`,r.workflowRuns.delete(e)},onError:f=>{o.on?.onError?.(f)}})}return{open:()=>L(!0),close:()=>L(!1),sendMessage:j,abort:()=>G.abort(o.baseUrl,r.sessionId),subscribeWorkflow:e=>{let a=null;return se(e).then(n=>a=n),()=>a?.()},destroy:()=>{W.destroy(),i.remove()}}}function we(o){return o==="bubble"?"floating":o==="panel"?"side":"inline"}var I=class extends HTMLElement{constructor(){super(...arguments);this.handle=null;this.authResolver=null;this.contextProvider=null;this.ragSourceId=null}static get observedAttributes(){return["base-url","token-endpoint","display","theme-mode","rag-source-id","session-id"]}connectedCallback(){let s=this.getAttribute("base-url");if(!s){console.error("[aimbase-chat] base-url attribute is required");return}let i=this.getAttribute("token-endpoint"),p=this.authResolver??(i?async()=>{let r=await fetch(i,{method:"POST",credentials:"include"});if(!r.ok)throw new Error(`token endpoint ${r.status}`);let g=await r.json();return g.data??g}:null);if(!p){console.error("[aimbase-chat] token-endpoint attribute or authResolver property is required");return}let b=this.getAttribute("display")??"inline",h=this.getAttribute("theme-mode"),u={baseUrl:s,authResolver:p,display:b,sessionId:this.getAttribute("session-id")??void 0,ragSourceId:this.ragSourceId??this.getAttribute("rag-source-id")??void 0,target:b==="inline"?this:void 0,theme:h?{mode:h}:void 0,contextProvider:this.contextProvider??void 0};this.handle=S(u)}disconnectedCallback(){this.handle?.destroy(),this.handle=null}subscribeWorkflow(s){return this.handle?.subscribeWorkflow(s)??(()=>{})}open(){this.handle?.open()}close(){this.handle?.close()}};function B(){typeof customElements>"u"||customElements.get("aimbase-chat")||customElements.define("aimbase-chat",I)}if(typeof window<"u")try{B()}catch{}function ke(o){return S(o)}return fe(Ee);})();
//# sourceMappingURL=aimbase-chat.umd.global.js.map