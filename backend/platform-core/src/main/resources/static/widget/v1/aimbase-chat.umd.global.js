"use strict";var AimbaseChat=(()=>{var D=Object.defineProperty;var V=Object.getOwnPropertyDescriptor;var Q=Object.getOwnPropertyNames;var X=Object.prototype.hasOwnProperty;var Y=(t,e)=>{for(var i in e)D(t,i,{get:e[i],enumerable:!0})},Z=(t,e,i,n)=>{if(e&&typeof e=="object"||typeof e=="function")for(let l of Q(e))!X.call(t,l)&&l!==i&&D(t,l,{get:()=>e[l],enumerable:!(n=V(e,l))||n.enumerable});return t};var ee=t=>Z(D({},"__esModule",{value:!0}),t);var ne={};Y(ne,{AimbaseChatElement:()=>S,createWidget:()=>E,defineAimbaseChat:()=>M,init:()=>oe});async function*U(t,e){if(!t.body)throw new Error("SSE response has no body");let i=t.body.getReader(),n=new TextDecoder("utf-8"),l="",m="message";try{for(;!e?.aborted;){let{value:u,done:b}=await i.read();if(b)break;l+=n.decode(u,{stream:!0});let s;for(;(s=l.indexOf(`

`))!==-1;){let f=l.slice(0,s);l=l.slice(s+2);let a="";for(let w of f.split(`
`))w.startsWith("event:")?m=w.slice(6).trim()||"message":w.startsWith("data:")?a+=w.slice(5).replace(/^ /,""):w.startsWith(":");a&&(yield{name:m,data:a},m="message")}}}finally{try{i.releaseLock()}catch{}}}var T=class{constructor(e){this.tokens=e;this.currentAbort=null}async sendMessage(e,i){this.currentAbort?.abort();let n=new AbortController;this.currentAbort=n;let l=await this.tokens.getToken(),m=[{type:"text",text:e.text}],u=[];e.context&&Object.keys(e.context).length>0&&u.push({role:"system",content:[{type:"text",text:`# \uD604\uC7AC \uD654\uBA74 \uCEE8\uD14D\uC2A4\uD2B8
${JSON.stringify(e.context,null,2)}`}]}),u.push({role:"user",content:m});let b={model:e.model??"auto",session_id:e.sessionId,stream:!0,messages:u};e.ragSourceId&&(b.rag_source_id=e.ragSourceId),e.connectionId&&(b.connection_id=e.connectionId);let s=await fetch(`${e.baseUrl}/api/v1/chat/completions`,{method:"POST",headers:{Authorization:`Bearer ${l}`,"Content-Type":"application/json",Accept:"text/event-stream"},body:JSON.stringify(b),signal:n.signal});if(!s.ok){let f=await s.text().catch(()=>"");throw new Error(`chat/completions ${s.status}: ${f.slice(0,200)}`)}for await(let f of U(s,n.signal))try{let a=JSON.parse(f.data);switch(f.name){case"delta":i({type:"delta",text:a.delta??""});break;case"thinking":i({type:"thinking",text:a.delta??""});break;case"tool_use_start":i({type:"tool_use_start",tool:{id:a.id,name:a.name,input:a.input}});break;case"tool_result":i({type:"tool_result",toolResult:{tool_use_id:a.tool_use_id,output:a.output,is_error:!!a.is_error}});break;case"done":i({type:"done",done:{rag_used:!!a.rag_used,citations:Array.isArray(a.citations)?a.citations:[]}});break;default:break}}catch(a){i({type:"error",error:`parse error: ${a.message}`})}}async abort(e,i){this.currentAbort?.abort(),this.currentAbort=null;let n=await this.tokens.getToken();try{await fetch(`${e}/api/v1/chat/${encodeURIComponent(i)}/abort`,{method:"POST",headers:{Authorization:`Bearer ${n}`}})}catch{}}};var B=`
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
`;var _=class{constructor(e,i){this.resolver=e;this.token=null;this.expiresAtMs=0;this.refreshTimer=null;this.onExpiring=i}async getToken(){return(!this.token||Date.now()>=this.expiresAtMs-3e4)&&await this.refresh(),this.token.token}async refresh(){let e=await this.resolver();this.token=e,this.expiresAtMs=typeof e.expires_at=="number"?e.expires_at:new Date(e.expires_at).getTime(),this.scheduleRefresh(e.refresh_after)}scheduleRefresh(e){this.refreshTimer&&clearTimeout(this.refreshTimer);let i=Math.max(1e4,e*1e3);this.refreshTimer=setTimeout(()=>{try{this.onExpiring?.()}catch{}this.refresh().catch(()=>{})},i)}destroy(){this.refreshTimer&&clearTimeout(this.refreshTimer),this.refreshTimer=null,this.token=null}};var $=class{constructor(e){this.tokens=e}async subscribe(e,i,n){let l=await this.tokens.getToken(),m=`${e}/api/v1/workflows/runs/${encodeURIComponent(i)}/subscribe?access_token=${encodeURIComponent(l)}`,u=new EventSource(m);return u.addEventListener("workflow.snapshot",b=>{try{n.onSnapshot?.(JSON.parse(b.data))}catch(s){n.onError?.(s)}}),u.addEventListener("workflow.step",b=>{try{n.onStep?.(JSON.parse(b.data))}catch(s){n.onError?.(s)}}),u.addEventListener("workflow.approval",b=>{try{n.onApproval?.(JSON.parse(b.data))}catch(s){n.onError?.(s)}}),u.addEventListener("workflow.done",b=>{try{n.onDone?.(JSON.parse(b.data))}catch(s){n.onError?.(s)}finally{u.close()}}),u.onerror=()=>{u.readyState===EventSource.CLOSED&&n.onError?.(new Error("workflow SSE closed"))},()=>u.close()}async fetchChunk(e,i,n){let l=await this.tokens.getToken(),m=await fetch(`${e}/api/v1/knowledge-sources/${encodeURIComponent(i)}/chunks/${encodeURIComponent(n)}`,{headers:{Authorization:`Bearer ${l}`}});if(!m.ok)throw new Error(`chunks ${m.status}`);return(await m.json()).data??{}}};function d(t,e,i){let n=document.createElement(t);return e&&(n.className=e),i&&(n.textContent=i),n}function te(){try{return self.crypto.randomUUID()}catch{return`sess-${Date.now()}-${Math.random().toString(36).slice(2,10)}`}}function E(t){let e=t.display??"bubble",i=t.theme?.mode??"auto",n=document.createElement("div");n.setAttribute("data-aimbase-widget","true"),i==="dark"&&n.setAttribute("data-theme","dark"),i==="light"&&n.setAttribute("data-theme","light");let l=typeof t.target=="string"?document.querySelector(t.target):t.target??null;if((e==="inline"||e==="panel")&&!l)throw new Error(`display='${e}' requires options.target`);(l??document.body).appendChild(n);let m=n.attachShadow({mode:"open"}),u=document.createElement("style");if(u.textContent=B,m.appendChild(u),t.theme?.cssVars){let r=Object.entries(t.theme.cssVars).map(([o,h])=>`${o}: ${h};`).join(" "),c=document.createElement("style");c.textContent=`:host { ${r} }`,m.appendChild(c)}let b=d("div","root");m.appendChild(b);let s={open:e!=="bubble",sessionId:t.sessionId??te(),ragSourceId:t.ragSourceId,currentAssistantDiv:null,currentAssistantText:"",workflowRuns:new Map},f=null;e==="bubble"&&(f=d("button","bubble-btn","\u{1F4AC}"),f.setAttribute("aria-label","Open Aimbase chat"),f.addEventListener("click",()=>A(!0)),b.appendChild(f));let a=d("div",`panel ${re(e)} ${s.open?"":"hidden"}`);b.appendChild(a);let w=d("div","header");if(w.appendChild(d("div","title","Aimbase Chat")),e==="bubble"||e==="panel"){let r=d("button","close","\xD7");r.addEventListener("click",()=>A(!1)),w.appendChild(r)}a.appendChild(w);let v=d("div","messages");a.appendChild(v);let R=d("div","composer"),k=document.createElement("textarea");k.placeholder="\uBA54\uC2DC\uC9C0\uB97C \uC785\uB825\uD558\uC138\uC694\u2026 (Enter \uC804\uC1A1, Shift+Enter \uC904\uBC14\uAFC8)",k.rows=1;let C=d("button","send-btn","\uC804\uC1A1");R.appendChild(k),R.appendChild(C),a.appendChild(R);function A(r){s.open=r,r?a.classList.remove("hidden"):a.classList.add("hidden")}function y(r,c){let o=d("div",`msg ${r}`,c);return v.appendChild(o),v.scrollTop=v.scrollHeight,o}function N(r){if(!r?.length)return;let c=d("div","citations");r.forEach((o,h)=>{let x=d("button","citation",`[${o.index??h+1}] ${o.document_name??o.source_id}`);x.title=o.content_preview,x.addEventListener("click",()=>j(o)),c.appendChild(x)}),v.appendChild(c),v.scrollTop=v.scrollHeight}async function j(r){let c=a.querySelector(".citation-preview");c&&c.remove();let o=d("div","citation-preview"),h=d("button","close","\xD7");h.addEventListener("click",()=>o.remove()),o.appendChild(h),o.appendChild(d("h4","",r.document_name??r.source_id)),o.appendChild(d("div","meta",`score ${r.score.toFixed(2)}${r.page_number?` \xB7 page ${r.page_number}`:""}`));let x=d("div","content",r.content_preview);if(o.appendChild(x),a.appendChild(o),r.chunk_id)try{let I=await z.fetchChunk(t.baseUrl,r.source_id,r.chunk_id);typeof I.content=="string"&&(x.textContent=I.content)}catch{}}function J(){return s.currentAssistantDiv||(s.currentAssistantDiv=y("assistant",""),s.currentAssistantText=""),s.currentAssistantDiv}function H(){s.currentAssistantDiv=null,s.currentAssistantText=""}let W=new _(t.authResolver,t.on?.onTokenExpiring),O=new T(W),z=new $(W);async function L(r){let c=r.trim();if(c){y("user",c),H(),C.setAttribute("disabled","true");try{await O.sendMessage({baseUrl:t.baseUrl,sessionId:s.sessionId,text:c,ragSourceId:s.ragSourceId,context:t.contextProvider?.()},o=>{switch(t.on?.onMessage?.(o),o.type){case"delta":{let h=J();s.currentAssistantText+=o.text??"",h.textContent=s.currentAssistantText,v.scrollTop=v.scrollHeight;break}case"thinking":y("thinking",`\u{1F4AD} ${o.text??""}`);break;case"tool_use_start":y("tool",`\u{1F6E0} ${o.tool?.name??"tool"} \uC2E4\uD589\u2026`);break;case"tool_result":o.toolResult?.is_error&&y("error",`\uB3C4\uAD6C \uC5D0\uB7EC: ${o.toolResult.output.slice(0,200)}`);break;case"done":o.done?.citations?.length&&N(o.done.citations),H();break;case"error":y("error",o.error??"unknown error");break}})}catch(o){y("error",o.message),t.on?.onError?.(o)}finally{C.removeAttribute("disabled")}}}C.addEventListener("click",()=>{L(k.value),k.value=""}),k.addEventListener("keydown",r=>{r.key==="Enter"&&!r.shiftKey&&(r.preventDefault(),L(k.value),k.value="")});async function q(r){let c=d("div","workflow-panel"),o=d("div","title",`\uC6CC\uD06C\uD50C\uB85C\uC6B0 ${r.slice(0,8)}\u2026`);c.appendChild(o);let h=d("div","steps");c.appendChild(h),v.appendChild(c);let x=new Map;return s.workflowRuns.set(r,{stepEl:c,steps:x}),await z.subscribe(t.baseUrl,r,{onSnapshot:p=>{o.textContent=`\uC6CC\uD06C\uD50C\uB85C\uC6B0 ${p.workflow_id??r.slice(0,8)} \u2014 ${p.status}`},onStep:p=>{t.on?.onWorkflowStep?.(p);let g=x.get(p.step_id);g||(g=d("div","workflow-step"),x.set(p.step_id,g),h.appendChild(g));let K=p.status==="running"?"running":p.status==="completed"?"completed":"failed";g.innerHTML="";let G=d("span",`dot ${K}`),F=d("span","label",`${p.step_id}${p.duration_ms?` \xB7 ${p.duration_ms}ms`:""}`);if(g.appendChild(G),g.appendChild(F),p.sub_workflow_id){let P=d("span","sub",` \u2192 ${p.sub_workflow_id}`);P.style.color="var(--aimbase-muted)",g.appendChild(P)}},onApproval:p=>{t.on?.onApprovalRequired?.(p);let g=d("div","workflow-step");g.innerHTML=`\u23F8 \uC2B9\uC778 \uB300\uAE30: <b>${p.step_id}</b> \u2014 ${p.reason??p.policy_id}`,g.style.color="#b45309",h.appendChild(g)},onDone:p=>{o.textContent=`\uC6CC\uD06C\uD50C\uB85C\uC6B0 ${r.slice(0,8)}\u2026 \u2014 ${p.status} (${p.duration_ms}ms)`,s.workflowRuns.delete(r)},onError:p=>{t.on?.onError?.(p)}})}return{open:()=>A(!0),close:()=>A(!1),sendMessage:L,abort:()=>O.abort(t.baseUrl,s.sessionId),subscribeWorkflow:r=>{let c=null;return q(r).then(o=>c=o),()=>c?.()},destroy:()=>{W.destroy(),n.remove()}}}function re(t){return t==="bubble"?"floating":t==="panel"?"side":"inline"}var S=class extends HTMLElement{constructor(){super(...arguments);this.handle=null;this.authResolver=null;this.contextProvider=null;this.ragSourceId=null}static get observedAttributes(){return["base-url","token-endpoint","display","theme-mode","rag-source-id","session-id"]}connectedCallback(){let i=this.getAttribute("base-url");if(!i){console.error("[aimbase-chat] base-url attribute is required");return}let n=this.getAttribute("token-endpoint"),l=this.authResolver??(n?async()=>{let s=await fetch(n,{method:"POST",credentials:"include"});if(!s.ok)throw new Error(`token endpoint ${s.status}`);let f=await s.json();return f.data??f}:null);if(!l){console.error("[aimbase-chat] token-endpoint attribute or authResolver property is required");return}let m=this.getAttribute("display")??"inline",u=this.getAttribute("theme-mode"),b={baseUrl:i,authResolver:l,display:m,sessionId:this.getAttribute("session-id")??void 0,ragSourceId:this.ragSourceId??this.getAttribute("rag-source-id")??void 0,target:m==="inline"?this:void 0,theme:u?{mode:u}:void 0,contextProvider:this.contextProvider??void 0};this.handle=E(b)}disconnectedCallback(){this.handle?.destroy(),this.handle=null}subscribeWorkflow(i){return this.handle?.subscribeWorkflow(i)??(()=>{})}open(){this.handle?.open()}close(){this.handle?.close()}};function M(){typeof customElements>"u"||customElements.get("aimbase-chat")||customElements.define("aimbase-chat",S)}if(typeof window<"u")try{M()}catch{}function oe(t){return E(t)}return ee(ne);})();
//# sourceMappingURL=aimbase-chat.umd.global.js.map