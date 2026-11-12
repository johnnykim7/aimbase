import{a as ct}from"./chunk-53NLFBUF.js";import{a as Dt}from"./chunk-7URSO5OV.js";import"./chunk-5IV3NQOF.js";var We=class{constructor(o){this.tokens=o}async upload(o,l){let d=await this.tokens.getToken(),p=new FormData;return p.append("session_id",o.sessionId),p.append("file",o.file,o.file.name),new Promise((g,b)=>{let y=new XMLHttpRequest;y.open("POST",`${o.baseUrl}/api/v1/chat/attachments`),y.setRequestHeader("Authorization",`Bearer ${d}`),l&&y.upload&&y.upload.addEventListener("progress",m=>{if(m.lengthComputable){let v=Math.round(m.loaded/m.total*100);l(v)}}),y.onload=()=>{if(y.status>=200&&y.status<300)try{let m=JSON.parse(y.responseText),v=m.data??m;g(v)}catch(m){b(new Error(`invalid response JSON: ${m.message}`))}else{let m=Fn(y.responseText)??`status ${y.status}`;b(new Error(m))}},y.onerror=()=>b(new Error("network error")),y.send(p)})}async delete(o){let l=await this.tokens.getToken(),d=`${o.baseUrl}/api/v1/chat/attachments/${encodeURIComponent(o.attachmentId)}?session_id=${encodeURIComponent(o.sessionId)}`,p=await fetch(d,{method:"DELETE",headers:{Authorization:`Bearer ${l}`}});if(!p.ok&&p.status!==404){let g=await p.text().catch(()=>"");throw new Error(`delete attachment ${p.status}: ${g.slice(0,200)}`)}}};function Fn(n){if(!n)return null;try{let o=JSON.parse(n);return typeof o.error=="string"?o.error:typeof o.message=="string"?o.message:null}catch{return n.slice(0,200)}}async function*Ot(n,o){if(!n.body)throw new Error("SSE response has no body");let l=n.body.getReader(),d=new TextDecoder("utf-8"),p="",g="message";try{for(;!o?.aborted;){let{value:b,done:y}=await l.read();if(y)break;p+=d.decode(b,{stream:!0});let m;for(;(m=p.indexOf(`

`))!==-1;){let v=p.slice(0,m);p=p.slice(m+2);let h="";for(let u of v.split(`
`))u.startsWith("event:")?g=u.slice(6).trim()||"message":u.startsWith("data:")?h+=u.slice(5).replace(/^ /,""):u.startsWith(":");h&&(yield{name:g,data:h},g="message")}}}finally{try{l.releaseLock()}catch{}}}var ue=class extends Error{constructor(l,d){super(d);this.status=l;this.code=d;this.name="WidgetApiError"}};async function pt(n){let o=await n.text().catch(()=>""),l=o.slice(0,200);try{let d=JSON.parse(o);l=d.error??d.message??l}catch{}return new ue(n.status,l||`HTTP ${n.status}`)}var qe=class{constructor(o,l){this.tokens=o;this.baseUrl=l}async call(o,l,d){let p=await this.tokens.getToken(),g=await fetch(`${this.baseUrl}${l}`,{method:o,headers:{Authorization:`Bearer ${p}`,...d!==void 0?{"Content-Type":"application/json"}:{}},body:d!==void 0?JSON.stringify(d):void 0});if(!g.ok)throw await pt(g);return(await g.json().catch(()=>({}))).data}settings(o){let l=o?`?connection_id=${encodeURIComponent(o)}`:"";return this.call("GET",`/api/v1/chat/widget-settings${l}`)}saveFeedback(o){return this.call("PUT","/api/v1/chat/feedback",{session_id:o.sessionId,turn_id:o.turnId,rating:o.rating,reason_codes:o.reasonCodes??[],comment:o.comment??null})}deleteFeedback(o){return this.call("DELETE",`/api/v1/chat/feedback?turn_id=${encodeURIComponent(o)}`)}followUps(o,l,d){return this.call("POST",`/api/v1/chat/sessions/${encodeURIComponent(o)}/follow-ups`,{turn_id:l,connection_id:d??null})}};var Hn=8e3,Ve=class{constructor(o){this.tokens=o;this.currentAbort=null;this.stopTimer=null}async sendMessage(o,l){let d=this.currentAbort,p=new AbortController;this.currentAbort=p,d?.abort();let g=await this.tokens.getToken(),b=[];for(let h of o.attachments??[])b.push({type:h.mediaType==="application/pdf"?"document":"image",attachment_id:h.attachmentId});o.text&&o.text.length>0&&b.push({type:"text",text:o.text});let y=[];o.context&&Object.keys(o.context).length>0&&y.push({role:"system",content:[{type:"text",text:`# \uD604\uC7AC \uD654\uBA74 \uCEE8\uD14D\uC2A4\uD2B8
${JSON.stringify(o.context,null,2)}`}]}),y.push({role:"user",content:b});let m={model:o.model??"auto",session_id:o.sessionId,stream:!0,messages:y};o.ragSourceId&&(m.rag_source_id=o.ragSourceId),o.connectionId&&(m.connection_id=o.connectionId),o.actionsEnabled&&(m.actions_enabled=!0),o.rewindFromSeq!==void 0&&(m.rewind_from_seq=o.rewindFromSeq),o.thinking&&(m.thinking=o.thinking);let v;try{v=await fetch(`${o.baseUrl}/api/v1/chat/completions`,{method:"POST",headers:{Authorization:`Bearer ${g}`,"Content-Type":"application/json",Accept:"text/event-stream"},body:JSON.stringify(m),signal:p.signal})}catch(h){if(h?.name==="AbortError"||p.signal.aborted)return;throw h}if(!v.ok)throw await pt(v);try{for await(let h of Ot(v,p.signal))try{let u=JSON.parse(h.data);switch(h.name){case"delta":l({type:"delta",text:u.delta??""});break;case"thinking":l({type:"thinking",text:u.delta??""});break;case"tool_use_start":l({type:"tool_use_start",tool:{id:u.id,name:u.name,input:u.input}});break;case"tool_result":l({type:"tool_result",toolResult:{tool_use_id:u.tool_use_id,output:u.output,is_error:!!u.is_error}});break;case"done":l({type:"done",done:{rag_used:!!u.rag_used,citations:Array.isArray(u.citations)?u.citations:[],artifacts:Array.isArray(u.artifacts)?u.artifacts:[],turn_id:typeof u.turn_id=="string"?u.turn_id:void 0,turn_seq:typeof u.turn_seq=="number"?u.turn_seq:void 0,model:typeof u.model=="string"?u.model:void 0}});break;default:break}}catch(u){l({type:"error",error:`parse error: ${u.message}`})}}catch(h){if(h?.name==="AbortError"||p.signal.aborted)return;throw h}finally{this.currentAbort===p&&(this.currentAbort=null,this.stopTimer&&clearTimeout(this.stopTimer),this.stopTimer=null)}}async stop(o,l){let d=this.currentAbort;if(!d)return;this.stopTimer&&clearTimeout(this.stopTimer),this.stopTimer=setTimeout(()=>{this.currentAbort===d&&d.abort()},Hn);let p=await this.tokens.getToken();try{await fetch(`${o}/api/v1/chat/${encodeURIComponent(l)}/abort`,{method:"POST",headers:{Authorization:`Bearer ${p}`}})}catch{d.abort()}}async abort(o,l){this.currentAbort?.abort(),this.currentAbort=null;let d=await this.tokens.getToken();try{await fetch(`${o}/api/v1/chat/${encodeURIComponent(l)}/abort`,{method:"POST",headers:{Authorization:`Bearer ${d}`}})}catch{}}};async function Te(n){if(window.isSecureContext&&navigator.clipboard?.writeText)try{return await navigator.clipboard.writeText(n),!0}catch{}return Dn(n)}function Dn(n){let o=On(),l=document.createElement("textarea");l.value=n,l.setAttribute("readonly",""),l.style.cssText="position:fixed;top:0;left:0;width:1px;height:1px;opacity:0;pointer-events:none;",document.body.appendChild(l),l.select();let d=!1;try{d=document.execCommand("copy")}catch{d=!1}return l.remove(),o?.focus({preventScroll:!0}),d}function On(){let n=document.activeElement;for(;n?.shadowRoot?.activeElement;)n=n.shadowRoot.activeElement;return n instanceof HTMLElement?n:null}function Pn(n,o=16){return`<svg xmlns="http://www.w3.org/2000/svg" width="${o}" height="${o}" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">${n}</svg>`}var Un={chat:'<path d="M7.9 20A9 9 0 1 0 4 16.1L2 22Z"/>',attach:'<path d="m21.44 11.05-9.19 9.19a6 6 0 0 1-8.49-8.49l8.57-8.57A4 4 0 1 1 18 8.84l-8.59 8.57a2 2 0 0 1-2.83-2.83l8.49-8.48"/>',mic:'<path d="M12 2a3 3 0 0 0-3 3v7a3 3 0 0 0 6 0V5a3 3 0 0 0-3-3Z"/><path d="M19 10v2a7 7 0 0 1-14 0v-2"/><line x1="12" x2="12" y1="19" y2="22"/>',stop:'<rect x="6" y="6" width="12" height="12" rx="2" fill="currentColor" stroke="none"/>',up:'<path d="m5 12 7-7 7 7"/><path d="M12 19V5"/>',pen:'<path d="M12 3H5a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2v-7"/><path d="M18.4 2.6a1 1 0 0 1 3 3l-9 9a2 2 0 0 1-.85.5l-2.87.84a.5.5 0 0 1-.62-.62l.84-2.87a2 2 0 0 1 .5-.85z"/>',pencil:'<path d="M21.17 6.81a1 1 0 0 0-3.99-3.99L3.84 16.17a2 2 0 0 0-.5.83l-1.32 4.35a.5.5 0 0 0 .62.62l4.35-1.32a2 2 0 0 0 .83-.5z"/>',max:'<polyline points="15 3 21 3 21 9"/><polyline points="9 21 3 21 3 15"/><line x1="21" x2="14" y1="3" y2="10"/><line x1="3" x2="10" y1="21" y2="14"/>',min:'<polyline points="4 14 10 14 10 20"/><polyline points="20 10 14 10 14 4"/><line x1="14" x2="21" y1="10" y2="3"/><line x1="3" x2="10" y1="21" y2="14"/>',more:'<circle cx="12" cy="12" r="1"/><circle cx="19" cy="12" r="1"/><circle cx="5" cy="12" r="1"/>',copy:'<rect width="14" height="14" x="8" y="8" rx="2"/><path d="M4 16c-1.1 0-2-.9-2-2V4c0-1.1.9-2 2-2h10c1.1 0 2 .9 2 2"/>',check:'<path d="M20 6 9 17l-5-5"/>',redo:'<path d="M3 12a9 9 0 0 1 9-9 9.75 9.75 0 0 1 6.74 2.74L21 8"/><path d="M21 3v5h-5"/><path d="M21 12a9 9 0 0 1-9 9 9.75 9.75 0 0 1-6.74-2.74L3 16"/><path d="M8 16H3v5"/>',like:'<path d="M7 10v12"/><path d="M15 5.88 14 10h5.83a2 2 0 0 1 1.92 2.56l-2.33 8A2 2 0 0 1 17.5 22H4a2 2 0 0 1-2-2v-8a2 2 0 0 1 2-2h2.76a2 2 0 0 0 1.79-1.11L12 2a3.13 3.13 0 0 1 3 3.88Z"/>',dislike:'<path d="M17 14V2"/><path d="M9 18.12 10 14H4.17a2 2 0 0 1-1.92-2.56l2.33-8A2 2 0 0 1 6.5 2H20a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2h-2.76a2 2 0 0 0-1.79 1.11L12 22a3.13 3.13 0 0 1-3-3.88Z"/>',x:'<path d="M18 6 6 18"/><path d="m6 6 12 12"/>',cdown:'<path d="m6 9 6 6 6-6"/>',cright:'<path d="m9 18 6-6-6-6"/>',cleft:'<path d="m15 18-6-6 6-6"/>',arrowdown:'<path d="M12 5v14"/><path d="m19 12-7 7-7-7"/>',spark:'<path d="M9.94 15.5A2 2 0 0 0 8.5 14.06l-6.14-1.58a.5.5 0 0 1 0-.96L8.5 9.94A2 2 0 0 0 9.94 8.5l1.58-6.14a.5.5 0 0 1 .96 0L14.06 8.5a2 2 0 0 0 1.44 1.44l6.14 1.58a.5.5 0 0 1 0 .96L15.5 14.06a2 2 0 0 0-1.44 1.44l-1.58 6.14a.5.5 0 0 1-.96 0z"/>',text:'<path d="M4 7V4h16v3"/><path d="M9 20h6"/><path d="M12 4v16"/>'},I=(n,o=16)=>Pn(Un[n],o);function Pt(n){return n.replace(/&/g,"&amp;").replace(/</g,"&lt;").replace(/>/g,"&gt;").replace(/"/g,"&quot;").replace(/'/g,"&apos;")}function Ut(n){let o=n,l="";do l=String.fromCharCode(65+o%26)+l,o=Math.floor(o/26)-1;while(o>=0);return l}function jn(n){return n.trim()===""?!1:/^-?\d+(\.\d+)?$/.test(n.trim())}async function jt(n,o="Sheet1"){let{zipSync:l,strToU8:d}=await import("./browser-TRYNJ3P5.js"),p=[],g=new Map,b=M=>{let H=g.get(M);if(H!==void 0)return H;let $=p.length;return p.push(M),g.set(M,$),$},y=[];n.forEach((M,H)=>{let $=[];M.forEach((he,T)=>{let ee=`${Ut(T)}${H+1}`,q=he??"";if(jn(q))$.push(`<c r="${ee}" t="n"><v>${q.trim()}</v></c>`);else if(q==="")$.push(`<c r="${ee}"/>`);else{let Le=b(q);$.push(`<c r="${ee}" t="s"><v>${Le}</v></c>`)}}),y.push(`<row r="${H+1}">${$.join("")}</row>`)});let m=n.reduce((M,H)=>Math.max(M,H.length),0),v=n.length?`A1:${Ut(Math.max(0,m-1))}${n.length}`:"A1",h=`<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
<Default Extension="xml" ContentType="application/xml"/>
<Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>
<Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>
<Override PartName="/xl/sharedStrings.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sharedStrings+xml"/>
<Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>
</Types>`,u=`<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>
</Relationships>`,C=`<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
<sheets><sheet name="${Pt(o).slice(0,31)||"Sheet1"}" sheetId="1" r:id="rId1"/></sheets>
</workbook>`,R=`<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/>
<Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>
<Relationship Id="rId3" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/sharedStrings" Target="sharedStrings.xml"/>
</Relationships>`,A=`<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
<dimension ref="${v}"/>
<sheetData>${y.join("")}</sheetData>
</worksheet>`,B=`<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<sst xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" count="${p.length}" uniqueCount="${p.length}">
${p.map(M=>`<si><t xml:space="preserve">${Pt(M)}</t></si>`).join("")}
</sst>`,me={"[Content_Types].xml":d(h),"_rels/.rels":d(u),"xl/workbook.xml":d(C),"xl/_rels/workbook.xml.rels":d(R),"xl/worksheets/sheet1.xml":d(A),"xl/sharedStrings.xml":d(B),"xl/styles.xml":d(`<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
<fonts count="1"><font><sz val="11"/><name val="Calibri"/></font></fonts>
<fills count="1"><fill><patternFill patternType="none"/></fill></fills>
<borders count="1"><border/></borders>
<cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs>
<cellXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/></cellXfs>
</styleSheet>`)},fe=l(me,{level:6});return new Blob([fe.slice()],{type:"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"})}var Bt=!1;function Bn(){Bt||(ct.setOptions({gfm:!0,breaks:!0}),Bt=!0)}var ut=null;async function Nn(n){return ut||(ut=import("./mermaid.core-RILUNMKV.js").then(o=>{let l=o.default;return l.initialize({startOnLoad:!1,securityLevel:"strict",theme:n==="dark"?"dark":"default",fontFamily:"inherit"}),l})),ut}var Wn=0;function qn(n){return Dt.sanitize(n,{ALLOWED_TAGS:["p","br","hr","div","span","strong","b","em","i","del","s","u","mark","sub","sup","h1","h2","h3","h4","h5","h6","ul","ol","li","blockquote","a","code","pre","table","thead","tbody","tr","th","td","img"],ALLOWED_ATTR:["href","title","class","src","alt","align","colspan","rowspan"],ALLOW_DATA_ATTR:!1,ALLOWED_URI_REGEXP:/^(?:(?:https?|mailto|tel):|[^a-z]|[a-z+.-]+(?:[^a-z+.:-]|$)|data:image\/)/i})}function Vn(n){n.querySelectorAll("a[href]").forEach(o=>{o.setAttribute("target","_blank"),o.setAttribute("rel","noopener noreferrer nofollow")})}function Xn(n){let o=[];return n.querySelectorAll("tr").forEach(l=>{let d=[];l.querySelectorAll("th, td").forEach(p=>{d.push((p.textContent??"").trim())}),d.length&&o.push(d)}),o}function Jn(n){return/[",\r\n]/.test(n)?`"${n.replace(/"/g,'""')}"`:n}function Gn(n){return n.map(o=>o.map(Jn).join(",")).join(`\r
`)}function Nt(n,o){let l=URL.createObjectURL(n),d=document.createElement("a");d.href=l,d.download=o,d.style.display="none",document.body.appendChild(d),d.click(),setTimeout(()=>{d.remove(),URL.revokeObjectURL(l)},0)}var Kn=0;function Zn(n){n.querySelectorAll("table").forEach(o=>{let l=Xn(o);if(!l.length)return;let d=++Kn,p=document.createElement("div");p.className="rich-table-wrap";let g=document.createElement("div");g.className="rich-table-toolbar";let b=(u,k)=>{let C=document.createElement("button");return C.type="button",C.className="rich-tbl-btn",C.textContent=u,C.title=k,C},y=b("CSV","CSV \uB2E4\uC6B4\uB85C\uB4DC");y.addEventListener("click",()=>{let u=new Blob(["\uFEFF"+Gn(l)],{type:"text/csv;charset=utf-8"});Nt(u,`table-${d}.csv`)});let m=b("\uC5D1\uC140","\uC5D1\uC140(.xlsx) \uB2E4\uC6B4\uB85C\uB4DC");m.addEventListener("click",async()=>{m.disabled=!0;let u=m.textContent;m.textContent="\u2026";try{let k=await jt(l,`Sheet${d}`);Nt(k,`table-${d}.xlsx`)}catch{m.textContent="\uC2E4\uD328",setTimeout(()=>{m.textContent=u},1500),m.disabled=!1;return}m.textContent=u,m.disabled=!1});let v=b("\uBCF5\uC0AC","\uD45C\uB97C \uD074\uB9BD\uBCF4\uB4DC\uC5D0 \uBCF5\uC0AC (\uD0ED \uAD6C\uBD84)");v.addEventListener("click",async()=>{let u=l.map(k=>k.join("	")).join(`
`);await Te(u)&&Wt(v,"\uBCF5\uC0AC")}),g.appendChild(y),g.appendChild(m),g.appendChild(v),o.parentNode?.insertBefore(p,o),p.appendChild(g);let h=document.createElement("div");h.className="rich-table-scroll",p.appendChild(h),h.appendChild(o)})}function Wt(n,o){let l=n.textContent;n.textContent="\u2713",setTimeout(()=>{n.textContent=o??l},1200)}async function Yn(n,o){let d=Array.from(n.querySelectorAll("pre > code")).filter(p=>!Qn(p));if(d.length){for(let p of d){let g=p.parentElement;g.classList.add("rich-code");let b=document.createElement("button");b.type="button",b.className="rich-code-copy",b.textContent="\uBCF5\uC0AC",b.title="\uCF54\uB4DC \uBCF5\uC0AC",b.addEventListener("click",async()=>{await Te(p.textContent??"")&&Wt(b,"\uBCF5\uC0AC")}),g.appendChild(b)}try{let g=(await import("./common-AKK5NT2M.js")).default;for(let b of d){let m=Array.from(b.classList).find(h=>h.startsWith("language-"))?.slice(9),v=b.textContent??"";try{let h=m&&g.getLanguage(m)?g.highlight(v,{language:m}):g.highlightAuto(v);b.innerHTML=h.value,b.classList.add("hljs",`hljs-theme-${o}`)}catch{}}}catch{}}}function Qn(n){return n.classList.contains("language-mermaid")}async function er(n,o){let l=Array.from(n.querySelectorAll("pre > code.language-mermaid"));if(!l.length)return;let d;try{d=await Nn(o)}catch{return}for(let p of l){let g=p.parentElement,b=p.textContent??"",y=`aimbase-mermaid-${++Wn}`;try{let{svg:m}=await d.render(y,b),v=document.createElement("div");v.className="rich-mermaid",v.innerHTML=m,g.replaceWith(v)}catch{g.classList.add("rich-mermaid-error")}}}function qt(n,o,l){let d=l?.theme??"light";Bn();let p;try{p=ct.parse(o,{async:!1})}catch{n.textContent=o;return}let g=qn(p);n.innerHTML=g,n.classList.add("rich"),Vn(n),Zn(n),Yn(n,d),er(n,d)}var f={defaultTitle:"\uC0C8 \uB300\uD654",defaultEmptyTitle:"\uBB34\uC5C7\uC744 \uB3C4\uC640\uB4DC\uB9B4\uAE4C\uC694?",placeholder:"\uBA54\uC2DC\uC9C0\uB97C \uC785\uB825\uD558\uC138\uC694\u2026 (Enter \uC804\uC1A1, Shift+Enter \uC904\uBC14\uAFC8)",newConversation:"\uC0C8 \uB300\uD654",expand:"\uD06C\uAC8C \uBCF4\uAE30",more:"\uB354\uBCF4\uAE30",close:"\uB2EB\uAE30",fontSize:"\uAE00\uC790 \uD06C\uAE30",fontSmall:"\uC791\uAC8C",fontMedium:"\uBCF4\uD1B5",fontLarge:"\uD06C\uAC8C",attach:"\uD30C\uC77C \uCCA8\uBD80",model:"\uBAA8\uB378",deepThinking:"\uAE4A\uAC8C \uC0DD\uAC01\uD558\uAE30",send:"\uC804\uC1A1",stop:"\uC751\uB2F5 \uC911\uC9C0",cancel:"\uCDE8\uC18C",edit:"\uC218\uC815",copy:"\uBCF5\uC0AC",copied:"\uBCF5\uC0AC\uD588\uC2B5\uB2C8\uB2E4",copyFailed:"\uBCF5\uC0AC\uD558\uC9C0 \uBABB\uD588\uC2B5\uB2C8\uB2E4",regenerate:"\uB2E4\uC2DC \uC0DD\uC131",prevVersion:"\uC774\uC804",nextVersion:"\uB2E4\uC74C",like:"\uC88B\uC544\uC694",dislike:"\uC544\uC26C\uC6CC\uC694",reason:"\uC774\uC720",comment:"\uC758\uACAC",feedbackQuestion:"\uC5B4\uB5A4 \uC810\uC774 \uC544\uC26C\uC6E0\uB098\uC694?",feedbackSent:"\uC758\uACAC\uC744 \uBCF4\uB0C8\uC2B5\uB2C8\uB2E4",commentTooLong:n=>`${n}\uC790\uAE4C\uC9C0 \uC785\uB825\uD560 \uC218 \uC788\uC2B5\uB2C8\uB2E4`,feedbackFailed:"\uD3C9\uAC00\uB97C \uC800\uC7A5\uD558\uC9C0 \uBABB\uD588\uC2B5\uB2C8\uB2E4",toBottom:"\uB9E8 \uC544\uB798\uB85C",thinking:"\uC0DD\uAC01 \uC911",thoughtFor:n=>`${n}\uCD08 \uB3D9\uC548 \uC0DD\uAC01\uD588\uC2B5\uB2C8\uB2E4`,toolsWorking:"\uB3C4\uAD6C\uB97C \uC0AC\uC6A9\uD558\uB294 \uC911\uC785\uB2C8\uB2E4",toolsUsed:n=>`\uB3C4\uAD6C\uB97C ${n}\uBC88 \uC0AC\uC6A9\uD588\uC2B5\uB2C8\uB2E4`,stopped:"\uC751\uB2F5\uC744 \uC911\uC9C0\uD588\uC2B5\uB2C8\uB2E4",requestFailed:n=>`\uB2F5\uC744 \uBC1B\uC9C0 \uBABB\uD588\uC2B5\uB2C8\uB2E4: ${n}`,retry:"\uB2E4\uC2DC \uC2DC\uB3C4",editWarning:"\uC774 \uC9C8\uBB38 \uB4A4 \uB300\uD654\uB294 \uC9C0\uC6CC\uC9D1\uB2C8\uB2E4",attachmentLabel:n=>`\uCCA8\uBD80: ${n}`,dropHere:"\uD30C\uC77C\uC744 \uB193\uC544 \uCCA8\uBD80\uD558\uC138\uC694",removeAttachment:"\uCCA8\uBD80 \uC81C\uAC70",errors:{session_busy:"\uC55E\uC758 \uC751\uB2F5\uC774 \uC544\uC9C1 \uB05D\uB098\uC9C0 \uC54A\uC558\uC2B5\uB2C8\uB2E4. \uC7A0\uC2DC \uD6C4 \uB2E4\uC2DC \uC2DC\uB3C4\uD558\uC138\uC694",model_not_allowed:"\uC774 \uC5F0\uACB0\uC5D0\uC11C \uC0AC\uC6A9\uD560 \uC218 \uC5C6\uB294 \uBAA8\uB378\uC785\uB2C8\uB2E4",thinking_not_supported:"\uC774 \uC5F0\uACB0\uC740 \uAE4A\uAC8C \uC0DD\uAC01\uD558\uAE30\uB97C \uC9C0\uC6D0\uD558\uC9C0 \uC54A\uC2B5\uB2C8\uB2E4","rewind_invalid:not_turn_start":"\uC774 \uC9C8\uBB38\uBD80\uD130 \uB2E4\uC2DC \uBB3B\uC9C0 \uBABB\uD588\uC2B5\uB2C8\uB2E4. \uD398\uC774\uC9C0\uB97C \uB2E4\uC2DC \uBD88\uB7EC\uC628 \uB4A4 \uC2DC\uB3C4\uD558\uC138\uC694","rewind_invalid:no_session":"\uB300\uD654\uAC00 \uC11C\uBC84\uC5D0 \uC5C6\uC5B4 \uB2E4\uC2DC \uBB3B\uC9C0 \uBABB\uD588\uC2B5\uB2C8\uB2E4",rewind_before_compaction:"\uC624\uB798\uB41C \uC9C8\uBB38\uC774\uB77C \uC218\uC815\uD560 \uC218 \uC5C6\uC2B5\uB2C8\uB2E4. \uC0C8 \uB300\uD654\uC5D0\uC11C \uBB3C\uC5B4\uBCF4\uC138\uC694"}};var Ee=class{constructor(o){this.tokens=o}async transcribe(o,l){let d=await this.tokens.getToken(),p=nr(o.blob.type),g=o.filename??`rec.${p}`,b=new FormData;return b.append("session_id",o.sessionId),b.append("file",o.blob,g),o.language&&b.append("language",o.language),new Promise((y,m)=>{let v=new XMLHttpRequest;v.open("POST",`${o.baseUrl}/api/v1/chat/stt`),v.setRequestHeader("Authorization",`Bearer ${d}`),l&&v.upload&&v.upload.addEventListener("progress",h=>{h.lengthComputable&&l(Math.round(h.loaded/h.total*100))}),v.onload=()=>{if(v.status>=200&&v.status<300)try{let h=JSON.parse(v.responseText);y(h.data??h)}catch(h){m(new j("STT_INVALID_RESPONSE",`invalid JSON: ${h.message}`,v.status))}else{let{code:h,message:u}=tr(v.responseText,v.status);m(new j(h,u,v.status))}},v.onerror=()=>m(new j("STT_NETWORK","network error",0)),v.onabort=()=>m(new j("STT_ABORTED","aborted",0)),v.send(b)})}},j=class extends Error{constructor(l,d,p){super(d);this.code=l;this.status=p;this.name="SttError"}};function tr(n,o){if(!n)return{code:"STT_UNKNOWN",message:`status ${o}`};try{let l=JSON.parse(n),d=l.error??l.message;if(d){let p=d.match(/^(STT_[A-Z_]+):\s*(.*)$/);return p?{code:p[1],message:p[2]}:{code:"STT_UNKNOWN",message:d}}}catch{}return{code:"STT_UNKNOWN",message:n.slice(0,200)}}function nr(n){switch((n||"").toLowerCase().split(";")[0].trim()){case"audio/webm":return"webm";case"audio/mp4":return"mp4";case"audio/mpeg":return"mp3";case"audio/wav":return"wav";case"audio/ogg":return"ogg";default:return"bin"}}var Vt=`
:host {
  --aimbase-primary: #18181b;
  --aimbase-primary-hover: #3f3f46;
  --aimbase-on-primary: #ffffff;
  --aimbase-bg: #ffffff;
  --aimbase-fg: #18181b;
  --aimbase-muted: #71717a;
  --aimbase-border: #e4e4e7;
  --aimbase-border-strong: #d4d4d8;
  --aimbase-user-bg: #f4f4f5;
  --aimbase-assistant-bg: #fafafa;
  --aimbase-chip-bg: #f4f4f5;
  --aimbase-chip-hover: #e4e4e7;
  --aimbase-danger: #dc2626;
  --aimbase-radius: 10px;
  --aimbase-font: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, "Noto Sans KR", sans-serif;
  font-family: var(--aimbase-font);
  color: var(--aimbase-fg);
  font-size: 14px;
}
:host([data-theme="dark"]) {
  --aimbase-primary: #fafafa;
  --aimbase-primary-hover: #d4d4d8;
  --aimbase-on-primary: #18181b;
  --aimbase-bg: #18181b;
  --aimbase-fg: #f4f4f5;
  --aimbase-muted: #a1a1aa;
  --aimbase-border: #3f3f46;
  --aimbase-border-strong: #52525b;
  --aimbase-user-bg: #27272a;
  --aimbase-assistant-bg: #1f1f23;
  --aimbase-chip-bg: #27272a;
  --aimbase-chip-hover: #3f3f46;
  --aimbase-danger: #f87171;
}

.root { box-sizing: border-box; }
*, *::before, *::after { box-sizing: border-box; }

/* \uAE00\uC790 \uD06C\uAE30 \uC138 \uB2E8\uACC4. \uBD99\uC774\uB294 \uC571\uC740 theme.cssVars \uB85C \uAC12\uC744 \uBC14\uAFBC\uB2E4(BIZ-152). */
:host { --aimbase-font-size-small: 13px; --aimbase-font-size-medium: 14px; --aimbase-font-size-large: 16px; }
.panel { font-size: var(--aimbase-font-size-medium); }
:host([data-font-size="small"]) .panel { font-size: var(--aimbase-font-size-small); }
:host([data-font-size="large"]) .panel { font-size: var(--aimbase-font-size-large); }
button { font: inherit; color: inherit; }

/* \uBC84\uBE14 \uBAA8\uB4DC */
.bubble-btn {
  position: fixed; bottom: 20px; right: 20px;
  width: 56px; height: 56px; border-radius: 50%;
  background: var(--aimbase-primary); color: var(--aimbase-on-primary);
  border: none; cursor: pointer; box-shadow: 0 4px 12px rgba(0,0,0,.15);
  display: inline-flex; align-items: center; justify-content: center;
  z-index: 9998;
}
.bubble-btn:hover { background: var(--aimbase-primary-hover); }

.panel {
  position: relative;
  background: var(--aimbase-bg);
  border: 1px solid var(--aimbase-border);
  border-radius: var(--aimbase-radius);
  display: flex; flex-direction: column;
  overflow: hidden;
}
.panel.floating {
  position: fixed; bottom: 90px; right: 20px;
  width: 400px; height: 600px; max-height: 80vh;
  box-shadow: 0 10px 30px rgba(0,0,0,.18);
  z-index: 9999;
}
/* \uCE78 \uC548\uC5D0 \uB123\uC744 \uB54C\uB294 \uD638\uC2A4\uD2B8\uC640 root \uAC00 \uCE78 \uB192\uC774\uB97C \uC774\uC5B4\uBC1B\uC544\uC57C panel \uC758 100% \uAC00 \uCE78 \uB192\uC774\uAC00 \uB418\uACE0, \uB300\uD654\uAC00 \uCE78 \uC548\uC5D0\uC11C \uC2A4\uD06C\uB864\uB41C\uB2E4 */
:host([data-display="inline"]) { display: block; height: 100%; }
:host([data-display="inline"]) .root { height: 100%; }
.panel.inline { width: 100%; height: 100%; min-height: 400px; }
.panel.side { position: fixed; top: 0; right: 0; bottom: 0; width: 420px; max-width: 100vw; z-index: 9999; }
.panel.hidden { display: none; }
.panel.expanded {
  position: fixed; inset: 28px; width: auto; height: auto; max-height: none;
  z-index: 10000; box-shadow: 0 24px 60px rgba(0,0,0,.22);
}
.backdrop { display: none; position: fixed; inset: 0; background: rgba(24,24,27,.32); z-index: 9999; }
.backdrop.on { display: block; }

/* \uC544\uC774\uCF58 \uB2E8\uCD94\uC640 \uB9D0\uD48D\uC120 \uB3C4\uC6C0\uB9D0 */
.ib {
  display: inline-flex; align-items: center; justify-content: center;
  width: 30px; height: 30px; border: 0; background: transparent; border-radius: 8px;
  color: var(--aimbase-muted); cursor: pointer; flex-shrink: 0; padding: 0;
}
.ib:hover { background: var(--aimbase-chip-bg); color: var(--aimbase-fg); }
.ib.on, .ib[aria-pressed="true"] { color: var(--aimbase-fg); background: var(--aimbase-chip-bg); }
.ib.sm { width: 26px; height: 26px; border-radius: 6px; }
.ib:disabled { opacity: .4; cursor: default; background: transparent; }
.ib[hidden] { display: none; }
[data-tip] { position: relative; }
[data-tip]:not([data-tip=""]):hover::after {
  content: attr(data-tip); position: absolute; bottom: calc(100% + 6px); left: 50%; transform: translateX(-50%);
  background: #18181b; color: #fff; font-size: 11px; padding: 3px 7px; border-radius: 6px;
  white-space: nowrap; pointer-events: none; z-index: 30;
}
.btn {
  height: 28px; padding: 0 12px; border-radius: 8px; border: 1px solid var(--aimbase-border);
  background: var(--aimbase-bg); cursor: pointer; font-size: .9em; font-weight: 500;
  display: inline-flex; align-items: center; gap: 6px;
}
.btn:hover { background: var(--aimbase-chip-bg); }
.btn.pri { background: var(--aimbase-primary); color: var(--aimbase-on-primary); border-color: var(--aimbase-primary); }
.btn.pri:hover { background: var(--aimbase-primary-hover); }
.btn:disabled { opacity: .45; cursor: default; }

/* \uBA38\uB9AC\uC904 */
.hd {
  height: 44px; flex-shrink: 0; display: flex; align-items: center; gap: 2px;
  padding: 0 8px 0 14px; border-bottom: 1px solid var(--aimbase-border);
}
.ttl { flex: 1; min-width: 0; font-weight: 600; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }

/* \uB300\uD654 \uCE78 */
.scroll { flex: 1; overflow-y: auto; position: relative; }
.thread { max-width: 760px; margin: 0 auto; padding: 16px 16px 8px; display: flex; flex-direction: column; gap: 18px; }
.empty { max-width: 620px; margin: 0 auto; padding: 28px 4px 6px; text-align: center; }
.empty h3 { margin: 0 0 4px; font-size: 1.25em; font-weight: 700; letter-spacing: -.01em; }
.empty p { margin: 0 0 16px; color: var(--aimbase-muted); font-size: .92em; }
.sugs { display: grid; grid-template-columns: 1fr 1fr; gap: 8px; text-align: left; }
.sug {
  border: 1px solid var(--aimbase-border); background: var(--aimbase-bg); border-radius: 10px;
  padding: 9px 11px; cursor: pointer; font-size: .92em; line-height: 1.4; text-align: left;
}
.sug:hover { background: var(--aimbase-assistant-bg); border-color: var(--aimbase-border-strong); }

.u { align-self: flex-end; max-width: 82%; display: flex; flex-direction: column; align-items: flex-end; }
.u .bub { background: var(--aimbase-user-bg); border-radius: 14px; padding: 8px 12px; white-space: pre-wrap; line-height: 1.55; word-break: break-word; }
.u .acts { display: flex; justify-content: flex-end; gap: 2px; margin-top: 2px; align-items: center; }
.u .acts button { opacity: 0; transition: opacity .12s; }
.u:hover .acts button, .u:focus-within .acts button { opacity: 1; }
.time { font-size: 11px; color: var(--aimbase-muted); padding: 0 4px; white-space: nowrap; }
.u .edit { border: 1px solid var(--aimbase-border-strong); border-radius: 12px; padding: 8px; background: var(--aimbase-bg); width: min(520px, 72vw); }
.u .edit textarea { width: 100%; border: 0; outline: none; resize: none; font: inherit; min-height: 48px; background: transparent; color: var(--aimbase-fg); }
.u .edit .warn { font-size: .85em; color: var(--aimbase-muted); margin: 2px 2px 6px; }
.u .edit .eb { display: flex; justify-content: flex-end; gap: 6px; }

.a { display: flex; flex-direction: column; align-items: stretch; min-width: 0; }
.fold { display: inline-flex; align-items: center; gap: 6px; font-size: .86em; color: var(--aimbase-muted); cursor: pointer; border: 0; background: none; padding: 2px 0; align-self: flex-start; }
.fold:hover { color: var(--aimbase-fg); }
.fold .cv { display: inline-flex; transition: transform .15s; }
.fold.open .cv { transform: rotate(90deg); }
.fold.live .lbl {
  background: linear-gradient(90deg, var(--aimbase-muted) 0%, var(--aimbase-fg) 50%, var(--aimbase-muted) 100%);
  background-size: 200% 100%; -webkit-background-clip: text; background-clip: text; color: transparent;
  animation: shim 1.4s linear infinite;
}
@keyframes shim { to { background-position: -200% 0; } }
.foldbody { display: none; margin: 4px 0 6px 6px; padding: 6px 10px; border-left: 2px solid var(--aimbase-border); color: var(--aimbase-muted); font-size: .9em; line-height: 1.55; white-space: pre-wrap; }
.foldbody.open { display: block; }
.trow { display: flex; flex-wrap: wrap; align-items: center; gap: 2px 8px; padding: 2px 0; font-size: .86em; white-space: normal; }
.trow .tn { color: var(--aimbase-fg); font-weight: 500; white-space: nowrap; }
.trow .ta { font-family: ui-monospace, Menlo, monospace; font-size: .8em; color: var(--aimbase-muted); flex: 1; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.trow .to { flex-basis: 100%; padding-left: 14px; font-size: .9em; color: var(--aimbase-muted); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.dot { width: 6px; height: 6px; border-radius: 50%; background: var(--aimbase-border-strong); flex-shrink: 0; }
.dot.run { background: var(--aimbase-fg); animation: pulse 1s infinite; }
.dot.fail { background: var(--aimbase-danger); }
@keyframes pulse { 50% { opacity: .3; } }
.md { line-height: 1.65; word-break: break-word; }
.md.raw { white-space: pre-wrap; }
.caret::after { content: ""; display: inline-block; width: 7px; height: 1em; background: var(--aimbase-fg); margin-left: 2px; vertical-align: -2px; animation: pulse .8s infinite; }
.note { font-size: .86em; color: var(--aimbase-muted); margin-top: 4px; }
.note.error { color: var(--aimbase-danger); }
.notice { align-self: stretch; font-size: .88em; color: var(--aimbase-danger); border: 1px solid var(--aimbase-border); border-radius: 10px; padding: 8px 10px; white-space: pre-wrap; }

.aacts { display: flex; align-items: center; flex-wrap: wrap; gap: 0; margin-top: 4px; }
.aacts .vn { font-size: .8em; color: var(--aimbase-muted); display: inline-flex; align-items: center; }
.aacts .mdl { font-size: 11px; color: var(--aimbase-muted); padding: 0 4px; white-space: nowrap; }
.panel.busy .needs-idle { display: none; }
.fb { margin-top: 6px; border: 1px solid var(--aimbase-border); border-radius: 10px; padding: 8px 10px; max-width: 520px; }
.fb .fq { font-size: .86em; color: var(--aimbase-muted); margin-bottom: 6px; }
.fb .fc { display: flex; gap: 6px; flex-wrap: wrap; margin-bottom: 6px; }
.chipb { height: 26px; padding: 0 10px; font-size: .84em; border: 1px solid var(--aimbase-border); background: var(--aimbase-bg); border-radius: 999px; cursor: pointer; color: var(--aimbase-muted); }
.chipb:hover { color: var(--aimbase-fg); background: var(--aimbase-chip-bg); }
.chipb.on { background: var(--aimbase-fg); color: var(--aimbase-bg); border-color: var(--aimbase-fg); }
.fb input { width: 100%; border: 1px solid var(--aimbase-border); border-radius: 8px; padding: 6px 8px; font: inherit; font-size: .92em; outline: none; margin-bottom: 6px; background: var(--aimbase-bg); color: var(--aimbase-fg); }
.fb .eb { display: flex; justify-content: flex-end; gap: 6px; }
.fb .warn { font-size: .85em; color: var(--aimbase-danger); margin: -2px 2px 6px; }
.fups { display: flex; flex-direction: column; gap: 2px; margin-top: 8px; align-items: flex-start; }
.fups:empty { display: none; }
.fup { border: 0; background: transparent; color: var(--aimbase-fg); opacity: .8; font-size: .92em; cursor: pointer; padding: 3px 0; display: inline-flex; gap: 6px; align-items: center; text-align: left; }
.fup:hover { opacity: 1; text-decoration: underline; text-underline-offset: 3px; }
.fup .ar { color: var(--aimbase-muted); display: inline-flex; }
.tobottom {
  position: absolute; left: 50%; transform: translateX(-50%); bottom: 12px; display: none; align-items: center; gap: 6px;
  height: 30px; padding: 0 12px; border-radius: 999px; border: 1px solid var(--aimbase-border);
  background: var(--aimbase-bg); box-shadow: 0 4px 14px rgba(0,0,0,.1); cursor: pointer; font-size: 12px; z-index: 5;
}
.tobottom.on { display: inline-flex; }
.stack { position: relative; flex: 1; min-height: 0; display: flex; flex-direction: column; }

.citations { display: flex; flex-wrap: wrap; gap: 6px; margin-top: 6px; }
.citation {
  background: var(--aimbase-chip-bg); border: 1px solid var(--aimbase-border); color: var(--aimbase-fg);
  padding: 4px 10px; border-radius: 12px; font-size: 12px; cursor: pointer;
}
.citation:hover { background: var(--aimbase-chip-hover); }
.artifacts { display: flex; flex-wrap: wrap; gap: 6px; margin-top: 6px; }
.artifact {
  background: var(--aimbase-chip-bg); border: 1px solid var(--aimbase-border); color: var(--aimbase-fg);
  padding: 6px 12px; border-radius: 12px; font-size: 12px; font-weight: 500; cursor: pointer;
  display: inline-flex; align-items: center; gap: 6px;
}
.artifact:hover { background: var(--aimbase-chip-hover); }
.workflow-panel { padding: 10px; border: 1px solid var(--aimbase-border); border-radius: 8px; font-size: 12px; background: var(--aimbase-assistant-bg); }
.workflow-step { display: flex; align-items: center; gap: 8px; padding: 4px 0; }
.workflow-step .dot { width: 8px; height: 8px; }
.workflow-step .dot.running { background: var(--aimbase-muted); animation: pulse 1.2s infinite; }
.workflow-step .dot.completed { background: var(--aimbase-fg); }
.workflow-step .dot.failed { background: var(--aimbase-danger); }
.citation-preview {
  position: absolute; right: 0; top: 0; bottom: 0; width: 60%;
  background: var(--aimbase-bg); border-left: 1px solid var(--aimbase-border);
  padding: 16px; overflow-y: auto; box-shadow: -4px 0 12px rgba(0,0,0,.1); z-index: 20;
}
.citation-preview h4 { margin: 0 0 8px; font-size: 1em; }
.citation-preview .meta { font-size: 12px; color: var(--aimbase-muted); margin-bottom: 10px; }
.citation-preview .content { font-size: .92em; line-height: 1.6; white-space: pre-wrap; }
.citation-preview .close { position: absolute; top: 8px; right: 8px; }

/* \uC785\uB825\uCE78 */
.cwrap { padding: 8px 12px 10px; flex-shrink: 0; }
.comp { max-width: 760px; margin: 0 auto; border: 1px solid var(--aimbase-border-strong); border-radius: 14px; background: var(--aimbase-bg); box-shadow: 0 1px 3px rgba(0,0,0,.04); }
.comp:focus-within { border-color: var(--aimbase-muted); }
.comp textarea {
  display: block; width: 100%; border: 0; outline: none; resize: none; font: inherit;
  padding: 10px 12px 2px; min-height: 36px; max-height: 180px; line-height: 1.5;
  background: transparent; color: var(--aimbase-fg);
}
.crow { display: flex; align-items: center; gap: 2px; padding: 4px 6px 6px; }
.crow .sp { flex: 1; }
.model { display: inline-flex; align-items: center; gap: 4px; height: 28px; padding: 0 8px; border: 0; background: transparent; border-radius: 8px; cursor: pointer; color: var(--aimbase-muted); font-size: .9em; max-width: 160px; }
.model span { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.model:hover { background: var(--aimbase-chip-bg); color: var(--aimbase-fg); }
.model[hidden] { display: none; }
.send {
  width: 30px; height: 30px; border-radius: 999px; border: 0; background: var(--aimbase-primary); color: var(--aimbase-on-primary);
  display: inline-flex; align-items: center; justify-content: center; cursor: pointer; margin-left: 2px; flex-shrink: 0; padding: 0;
}
.send:hover:not(:disabled) { background: var(--aimbase-primary-hover); }
.send:disabled { background: var(--aimbase-border-strong); cursor: default; }

.pop { position: absolute; z-index: 40; min-width: 200px; background: var(--aimbase-bg); border: 1px solid var(--aimbase-border); border-radius: 10px; box-shadow: 0 10px 30px rgba(0,0,0,.12); padding: 4px; display: none; }
.pop.on { display: block; }
.pop .mi { display: flex; align-items: center; gap: 9px; width: 100%; padding: 7px 9px; border: 0; background: transparent; border-radius: 7px; cursor: pointer; text-align: left; font-size: .9em; }
.pop .mi:hover { background: var(--aimbase-chip-bg); }
.pop .mi .mk { margin-left: auto; display: inline-flex; }
.pop .row { display: flex; align-items: center; gap: 8px; padding: 6px 9px; font-size: .9em; }
.pop .row .fsz { margin-left: auto; display: inline-flex; gap: 4px; }

/* \uCCA8\uBD80 */
.attachments { display: flex; flex-wrap: wrap; gap: 6px; padding: 8px 10px 0; }
.attachments:empty { display: none; }
.chip {
  display: inline-flex; align-items: center; gap: 6px; padding: 4px 8px 4px 4px;
  background: var(--aimbase-assistant-bg); border: 1px solid var(--aimbase-border); border-radius: 8px;
  max-width: 220px; font-size: 12px;
}
.chip.uploading { opacity: .75; }
.chip.error { border-color: var(--aimbase-danger); color: var(--aimbase-danger); }
.chip-thumb { width: 28px; height: 28px; border-radius: 4px; object-fit: cover; background: var(--aimbase-border); flex-shrink: 0; }
.chip-thumb.pdf {
  display: inline-flex; align-items: center; justify-content: center;
  color: var(--aimbase-muted); font-weight: 700; font-size: 10px;
  background: var(--aimbase-assistant-bg); border: 1px solid var(--aimbase-border);
}
.chip-label { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; flex: 1; min-width: 0; }
.chip-remove { background: none; border: none; cursor: pointer; color: var(--aimbase-muted); font-size: 14px; padding: 0 2px; line-height: 1; }
.chip-remove:hover { color: var(--aimbase-danger); }
.chip-spinner {
  width: 10px; height: 10px; border: 2px solid var(--aimbase-border); border-top-color: var(--aimbase-primary);
  border-radius: 50%; animation: chip-spin .8s linear infinite; flex-shrink: 0;
}
@keyframes chip-spin { to { transform: rotate(360deg); } }
.drop-overlay {
  position: absolute; inset: 0; border: 2px dashed var(--aimbase-border-strong);
  background: rgba(127,127,127,.08); border-radius: var(--aimbase-radius);
  display: flex; align-items: center; justify-content: center;
  font-size: 13px; color: var(--aimbase-fg); font-weight: 500; pointer-events: none; z-index: 10;
}

/* \uB9C8\uC774\uD06C\uC640 \uB179\uC74C */
.ib.rec-on { background: var(--aimbase-danger); color: #fff; }
.recording-overlay {
  position: absolute; left: 12px; right: 12px; bottom: 14px;
  background: var(--aimbase-bg); border: 1px solid var(--aimbase-border-strong); border-radius: 14px;
  padding: 10px 12px; display: flex; align-items: center; gap: 10px; z-index: 11; box-shadow: 0 4px 12px rgba(0,0,0,.08);
}
.recording-overlay.hidden { display: none; }
.rec-wave { flex: 1; font-family: monospace; color: var(--aimbase-danger); font-size: 14px; letter-spacing: 1px; animation: rec-pulse .9s ease-in-out infinite; }
.rec-timer { font-variant-numeric: tabular-nums; color: var(--aimbase-fg); font-size: 13px; font-weight: 600; }
@keyframes rec-pulse { 0%, 100% { opacity: .55; } 50% { opacity: 1; } }

.toast {
  position: absolute; left: 50%; bottom: 96px; transform: translateX(-50%) translateY(8px);
  background: #18181b; color: #fff; font-size: 12px; padding: 7px 12px; border-radius: 8px;
  opacity: 0; transition: all .18s; pointer-events: none; z-index: 50; white-space: nowrap;
}
.toast.on { opacity: 1; transform: translateX(-50%) translateY(0); }
/* \u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500
   CR-131: \uB9AC\uCE58 \uB80C\uB354\uB9C1 (\uB9C8\uD06C\uB2E4\uC6B4/\uD45C/\uCF54\uB4DC/mermaid)
   \uB2F5 \uBCF8\uBB38(.md.rich) \uC548\uC5D0\uC11C\uB9CC \uC801\uC6A9.
   \u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500 */
.md.rich { white-space: normal; }
.rich > *:first-child { margin-top: 0; }
.rich > *:last-child { margin-bottom: 0; }

.rich p { margin: 0 0 8px; line-height: 1.6; }
.rich h1, .rich h2, .rich h3, .rich h4, .rich h5, .rich h6 {
  margin: 14px 0 6px; line-height: 1.3; font-weight: 700;
}
.rich h1 { font-size: 1.35em; border-bottom: 1px solid var(--aimbase-border); padding-bottom: 4px; }
.rich h2 { font-size: 1.2em; border-bottom: 1px solid var(--aimbase-border); padding-bottom: 3px; }
.rich h3 { font-size: 1.1em; }
.rich h4, .rich h5, .rich h6 { font-size: 1em; }

.rich ul, .rich ol { margin: 0 0 8px; padding-left: 1.4em; }
.rich li { margin: 2px 0; line-height: 1.55; }
.rich li > ul, .rich li > ol { margin: 2px 0; }

.rich blockquote {
  margin: 0 0 8px; padding: 4px 12px;
  border-left: 3px solid var(--aimbase-border-strong);
  color: var(--aimbase-muted); background: rgba(127,127,127,.06);
  border-radius: 0 6px 6px 0;
}
.rich a { color: var(--aimbase-primary); text-decoration: underline; text-underline-offset: 2px; }
.rich a:hover { text-decoration: none; }
.rich hr { border: none; border-top: 1px solid var(--aimbase-border); margin: 12px 0; }
.rich img { max-width: 100%; border-radius: 6px; }

/* \uC778\uB77C\uC778 \uCF54\uB4DC */
.rich :not(pre) > code {
  background: rgba(127,127,127,.14);
  border-radius: 4px; padding: 1px 5px;
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: .88em; word-break: break-word;
}

/* \uCF54\uB4DC\uBE14\uB85D */
.rich pre.rich-code {
  position: relative; margin: 0 0 10px;
  background: #0d1117; color: #c9d1d9;
  border: 1px solid var(--aimbase-border);
  border-radius: 8px; padding: 12px 12px 10px;
  overflow-x: auto; font-size: 12.5px; line-height: 1.5;
}
.rich pre.rich-code code {
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  background: none; padding: 0; white-space: pre;
}
.rich-code-copy {
  position: absolute; top: 6px; right: 6px;
  background: rgba(255,255,255,.08); color: #c9d1d9;
  border: 1px solid rgba(255,255,255,.15);
  border-radius: 5px; padding: 2px 8px; font-size: 11px; cursor: pointer;
  opacity: 0; transition: opacity 120ms ease;
}
.rich pre.rich-code:hover .rich-code-copy { opacity: 1; }
.rich-code-copy:hover { background: rgba(255,255,255,.18); }

/* highlight.js \u2014 Shadow DOM \uACA9\uB9AC\uB77C \uD14C\uB9C8 CSS \uB97C \uC778\uB77C\uC778(github/github-dark \uC694\uC57D\uBCF8) */
.rich .hljs-comment, .rich .hljs-quote { color: #8b949e; font-style: italic; }
.rich .hljs-keyword, .rich .hljs-selector-tag, .rich .hljs-built_in,
.rich .hljs-name, .rich .hljs-tag { color: #ff7b72; }
.rich .hljs-string, .rich .hljs-title, .rich .hljs-section,
.rich .hljs-attribute, .rich .hljs-literal, .rich .hljs-template-tag,
.rich .hljs-template-variable, .rich .hljs-type, .rich .hljs-addition { color: #a5d6ff; }
.rich .hljs-number, .rich .hljs-symbol, .rich .hljs-bullet,
.rich .hljs-link, .rich .hljs-meta, .rich .hljs-selector-id,
.rich .hljs-selector-class { color: #79c0ff; }
.rich .hljs-function .hljs-title, .rich .hljs-title.function_ { color: #d2a8ff; }
.rich .hljs-attr, .rich .hljs-variable, .rich .hljs-property { color: #ffa657; }
.rich .hljs-emphasis { font-style: italic; }
.rich .hljs-strong { font-weight: 700; }
.rich .hljs-deletion { color: #ffdcd7; background: #67060c; }

/* \uD45C + \uB2E4\uC6B4\uB85C\uB4DC \uD234\uBC14 */
.rich-table-wrap { margin: 0 0 12px; }
.rich-table-toolbar {
  display: flex; gap: 6px; margin-bottom: 4px;
}
.rich-tbl-btn {
  background: transparent; color: var(--aimbase-muted);
  border: 1px solid var(--aimbase-border); border-radius: 6px;
  padding: 3px 10px; font-size: 11px; cursor: pointer;
  font-family: inherit; line-height: 1.4;
}
.rich-tbl-btn:hover { color: var(--aimbase-fg); background: var(--aimbase-chip-bg); }
.rich-tbl-btn:disabled { opacity: .55; cursor: default; }
.rich-table-scroll { overflow-x: auto; border-radius: 8px; }
.rich table {
  border-collapse: collapse; width: 100%; font-size: 12.5px;
  border: 1px solid var(--aimbase-border);
}
.rich th, .rich td {
  border: 1px solid var(--aimbase-border);
  padding: 6px 10px; text-align: left; vertical-align: top;
  white-space: nowrap;
}
.rich thead th {
  background: var(--aimbase-assistant-bg); font-weight: 700;
  position: sticky; top: 0;
}
.rich tbody tr:nth-child(even) { background: rgba(127,127,127,.05); }

/* mermaid */
.rich-mermaid {
  margin: 0 0 12px; padding: 10px;
  background: var(--aimbase-bg); border: 1px solid var(--aimbase-border);
  border-radius: 8px; overflow-x: auto; text-align: center;
}
.rich-mermaid svg { max-width: 100%; height: auto; }
.rich-mermaid-error { border-color: var(--aimbase-danger); }

`;var Xe=class{constructor(o,l){this.resolver=o;this.token=null;this.expiresAtMs=0;this.refreshTimer=null;this.onExpiring=l}async getToken(){return(!this.token||Date.now()>=this.expiresAtMs-3e4)&&await this.refresh(),this.token.token}hasScope(o){return!!this.token?.scopes?.includes(o)}async ensureLoaded(){this.token||await this.refresh()}async refresh(){let o=await this.resolver();this.token=o,this.expiresAtMs=typeof o.expires_at=="number"?o.expires_at:new Date(o.expires_at).getTime(),this.scheduleRefresh(o.refresh_after)}scheduleRefresh(o){this.refreshTimer&&clearTimeout(this.refreshTimer);let l=Math.max(1e4,o*1e3);this.refreshTimer=setTimeout(()=>{try{this.onExpiring?.()}catch{}this.refresh().catch(()=>{})},l)}destroy(){this.refreshTimer&&clearTimeout(this.refreshTimer),this.refreshTimer=null,this.token=null}};var Je=class{constructor(o){this.tokens=o}async subscribe(o,l,d){let p=await this.tokens.getToken(),g=`${o}/api/v1/workflows/runs/${encodeURIComponent(l)}/subscribe?access_token=${encodeURIComponent(p)}`,b=new EventSource(g);return b.addEventListener("workflow.snapshot",y=>{try{d.onSnapshot?.(JSON.parse(y.data))}catch(m){d.onError?.(m)}}),b.addEventListener("workflow.step",y=>{try{d.onStep?.(JSON.parse(y.data))}catch(m){d.onError?.(m)}}),b.addEventListener("workflow.approval",y=>{try{d.onApproval?.(JSON.parse(y.data))}catch(m){d.onError?.(m)}}),b.addEventListener("workflow.done",y=>{try{d.onDone?.(JSON.parse(y.data))}catch(m){d.onError?.(m)}finally{b.close()}}),b.onerror=()=>{b.readyState===EventSource.CLOSED&&d.onError?.(new Error("workflow SSE closed"))},()=>b.close()}async fetchChunk(o,l,d){let p=await this.tokens.getToken(),g=await fetch(`${o}/api/v1/knowledge-sources/${encodeURIComponent(l)}/chunks/${encodeURIComponent(d)}`,{headers:{Authorization:`Bearer ${p}`}});if(!g.ok)throw new Error(`chunks ${g.status}`);return(await g.json()).data??{}}};var rr="image/png,image/jpeg,image/gif,image/webp,application/pdf",or=10*1024*1024,ir=32*1024*1024,ar=4,sr=180,dr=48,lr=60,Xt=120,cr=500,Kt="aimbase-chat:font-size",mt=["small","medium","large"];function s(n,o,l){let d=document.createElement(n);return o&&(d.className=o),l&&(d.textContent=l),d}function L(n,o,l=""){let d=s("button",`ib ${l}`.trim());return d.type="button",d.innerHTML=I(n),d.setAttribute("aria-label",o),d.setAttribute("data-tip",o),d}function Jt(){try{return self.crypto.randomUUID()}catch{return`sess-${Date.now()}-${Math.random().toString(36).slice(2,10)}`}}function Gt(n){let o=g=>String(g).padStart(2,"0"),l=`${o(n.getHours())}:${o(n.getMinutes())}`,d=new Date;return n.getFullYear()===d.getFullYear()&&n.getMonth()===d.getMonth()&&n.getDate()===d.getDate()?l:`${n.getMonth()+1}/${n.getDate()} ${l}`}function pr(){try{let n=localStorage.getItem(Kt);return mt.includes(n)?n:null}catch{return null}}function ur(n){try{localStorage.setItem(Kt,n)}catch{}}function mr(){return{text:"",thinkingText:"",thinkingStartedAt:null,thinkingMs:null,tools:[],citations:[],artifacts:[],at:new Date,live:!0,stopped:!1,rejected:!1,feedbackOpen:!1,followUps:[]}}function Se(n){let o=n.display??"bubble",l=n.theme?.mode??"auto",d=document.createElement("div");d.setAttribute("data-aimbase-widget","true"),d.setAttribute("data-display",o),l==="dark"&&d.setAttribute("data-theme","dark"),l==="light"&&d.setAttribute("data-theme","light");function p(){return l==="dark"?"dark":l==="light"?"light":typeof window<"u"&&window.matchMedia?.("(prefers-color-scheme: dark)").matches?"dark":"light"}let g=typeof n.target=="string"?document.querySelector(n.target):n.target??null;if((o==="inline"||o==="panel")&&!g)throw new Error(`display='${o}' requires options.target`);(g??document.body).appendChild(d);let b=d.attachShadow({mode:"open"}),y=document.createElement("style");if(y.textContent=Vt,b.appendChild(y),n.theme?.cssVars){let e=Object.entries(n.theme.cssVars).map(([r,i])=>`${r}: ${i};`).join(" "),t=document.createElement("style");t.textContent=`:host { ${e} }`,b.appendChild(t)}let m=s("div","root");b.appendChild(m);let v=o!=="bubble",h=n.sessionId??Jt(),u=[],k=!1,C=null,R=[],A=null,B=n.model,Q=!1,me=!1,fe=!1,M=!0,H=new Map,$=null;o==="bubble"&&($=s("button","bubble-btn"),$.innerHTML=I("chat",24),$.setAttribute("aria-label","Open Aimbase chat"),$.addEventListener("click",()=>Re(!0)),m.appendChild($));let he=s("div","backdrop");he.addEventListener("click",()=>$e(!1)),m.appendChild(he);let T=s("div",`panel ${fr(o)} ${v?"":"hidden"}`);m.appendChild(T);let ee=s("div","hd"),q=s("div","ttl",f.defaultTitle),Le=L("pen",f.newConversation),be=L("max",f.expand);be.setAttribute("aria-pressed","false");let ht=L("more",f.more);if(ee.append(q,Le,be,ht),o==="bubble"||o==="panel"){let e=L("x",f.close);e.addEventListener("click",()=>Re(!1)),ee.appendChild(e)}T.appendChild(ee);let N=s("div","pop");N.style.top="40px",N.style.right="8px";let Ke=s("div","row");Ke.appendChild(s("span","",f.fontSize));let bt=s("span","fsz"),Zt={small:f.fontSmall,medium:f.fontMedium,large:f.fontLarge},gt=new Map;for(let e of mt){let t=s("button","chipb",Zt[e]);t.type="button",t.addEventListener("click",()=>{Lt(e),ur(e)}),gt.set(e,t),bt.appendChild(t)}Ke.appendChild(bt),N.appendChild(Ke),T.appendChild(N);let xt=s("div","stack"),V=s("div","scroll"),X=s("div","thread");V.appendChild(X);let te=s("button","tobottom");te.type="button",te.innerHTML=`${I("arrowdown",14)}<span>${f.toBottom}</span>`,te.addEventListener("click",()=>G(!0)),xt.append(V,te),T.appendChild(xt);let ne=s("div","empty");ne.appendChild(s("h3","",n.emptyTitle||f.defaultEmptyTitle)),n.emptySubtitle&&ne.appendChild(s("p","",n.emptySubtitle));let vt=(n.suggestions??[]).filter(e=>e&&e.trim()).slice(0,ar);if(vt.length){let e=s("div","sugs");for(let t of vt){let r=s("button","sug",t);r.type="button",r.addEventListener("click",()=>{De(t)}),e.appendChild(r)}ne.appendChild(e)}X.appendChild(ne);let yt=s("div","cwrap"),wt=s("div","comp"),Ze=s("div","attachments"),S=document.createElement("textarea");S.placeholder=f.placeholder,S.rows=1;let kt=s("div","crow"),Tt=L("attach",f.attach),O=document.createElement("input");O.type="file",O.accept=rr,O.multiple=!0,O.style.display="none";let J=s("button","model");J.type="button",J.setAttribute("aria-label",f.model),J.hidden=!0;let Et=s("span");J.appendChild(Et),J.insertAdjacentHTML("beforeend",I("cdown",12));let re=L("spark",f.deepThinking);re.setAttribute("aria-pressed","false"),re.hidden=!0;let P=L("mic","\uC74C\uC131 \uC785\uB825 \uC2DC\uC791");P.setAttribute("aria-pressed","false"),P.hidden=!0;let D=s("button","send");D.type="button",kt.append(Tt,O,s("span","sp"),J,re,P,D),wt.append(Ze,S,kt),yt.appendChild(wt),T.appendChild(yt);let U=s("div","pop");U.style.bottom="56px",U.style.right="12px",T.appendChild(U);let Ce=s("div","toast");T.appendChild(Ce);let ge=s("div","recording-overlay hidden");ge.setAttribute("role","status"),ge.setAttribute("aria-live","polite");let Yt=s("span","rec-wave","\u2581\u2583\u2585\u2587\u2585\u2583\u2581"),Ye=s("span","rec-timer","0:00"),Ae=s("button","btn",f.cancel);Ae.type="button",Ae.setAttribute("aria-label","\uB179\uC74C \uCDE8\uC18C");let Me=s("button","btn pri","\u25A0 \uC815\uC9C0");Me.type="button",Me.setAttribute("aria-label","\uB179\uC74C \uC815\uC9C0 \uD6C4 \uBCC0\uD658"),ge.append(Yt,Ye,Ae,Me),T.appendChild(ge);let W=new Xe(n.authResolver,n.on?.onTokenExpiring),_e=new Ve(W),St=new Je(W),Qe=new We(W),se=new qe(W,n.baseUrl);function Re(e){v=e,T.classList.toggle("hidden",!e),e||$e(!1)}function $e(e){fe=e,T.classList.toggle("expanded",e),he.classList.toggle("on",e),be.innerHTML=I(e?"min":"max"),be.setAttribute("aria-pressed",String(e))}function Lt(e){d.setAttribute("data-font-size",e),gt.forEach((t,r)=>t.classList.toggle("on",r===e))}function xe(){let e=N.classList.contains("on")||U.classList.contains("on");return N.classList.remove("on"),U.classList.remove("on"),e}let et=null;function de(e){Ce.textContent=e,Ce.classList.add("on"),et&&clearTimeout(et),et=setTimeout(()=>Ce.classList.remove("on"),1600)}function tt(e){q.textContent=e,q.title=e}function Qt(){return V.scrollHeight-V.scrollTop-V.clientHeight<dr}function G(e=!1){e&&(M=!0),M?(V.scrollTop=V.scrollHeight,te.classList.remove("on")):te.classList.add("on")}V.addEventListener("scroll",()=>{M=Qt(),M&&te.classList.remove("on")});function K(e){X.appendChild(s("div","notice",e)),G()}function en(e){return e instanceof ue?f.errors[e.code]??e.code:e?.message??String(e)}function nt(e){k=e,T.classList.toggle("busy",e),oe()}function oe(){if(k){D.innerHTML=I("stop",14),D.setAttribute("aria-label",f.stop),D.setAttribute("data-tip",f.stop),D.disabled=!1;return}D.innerHTML=I("up"),D.setAttribute("aria-label",f.send),D.setAttribute("data-tip",f.send);let e=R.some(i=>i.status==="uploading"),t=S.value.trim().length>0,r=R.some(i=>i.status==="ready");D.disabled=e||!t&&!r}function le(e){e.style.height="auto",e.style.height=`${Math.min(e.scrollHeight,sr)}px`}function Ct(e){if(!e)return"";let t=A?.models??[],r=t.find(a=>a.id===e);if(r)return r.label||r.id;let i=e.includes("/")?e.slice(e.lastIndexOf("/")+1):e,c=t.find(a=>a.id===i||a.id.endsWith(`/${i}`));return c?c.label||c.id:e}function rt(){let e=A?.models??[];J.hidden=e.length<2,Et.textContent=Ct(B),U.innerHTML="";for(let t of e){let r=s("button","mi");if(r.type="button",r.appendChild(s("span","",t.label||t.id)),t.id===B){let i=s("span","mk");i.innerHTML=I("check",14),r.appendChild(i)}r.addEventListener("click",()=>{B=t.id,xe(),rt()}),U.appendChild(r)}re.hidden=!A?.thinking_supported,re.setAttribute("aria-pressed",String(Q))}J.addEventListener("click",e=>{e.stopPropagation();let t=!U.classList.contains("on");xe(),U.classList.toggle("on",t)}),re.addEventListener("click",()=>{Q=!Q,re.setAttribute("aria-pressed",String(Q))}),ht.addEventListener("click",e=>{e.stopPropagation();let t=!N.classList.contains("on");xe(),N.classList.toggle("on",t)}),N.addEventListener("click",e=>e.stopPropagation()),U.addEventListener("click",e=>e.stopPropagation()),T.addEventListener("click",()=>xe()),be.addEventListener("click",()=>$e(!fe)),Le.addEventListener("click",()=>{mn()});async function tn(){try{A=await se.settings(n.connectionId);let e=A.models.map(t=>t.id);(!B||!e.includes(B))&&(B=A.default_model),Q=A.thinking_default===!0}catch(e){A=null,n.on?.onError?.(e)}rt()}function ze(e,t){let r=s("div"),i=s("button",`fold${t?" live":""}`);i.type="button";let c=s("span","cv");c.innerHTML=I("cright",12);let a=s("span","lbl",e);i.append(c,a);let x=s("div","foldbody");return i.addEventListener("click",()=>{i.classList.toggle("open"),x.classList.toggle("open")}),r.append(i,x),{wrap:r,btn:i,label:a,body:x}}function ot(e){let t=s("div","trow");return t.appendChild(s("span",`dot${e.status==="running"?" run":e.status==="failed"?" fail":""}`)),t.appendChild(s("span","tn",fn(e.name))),e.arg&&t.appendChild(s("span","ta",e.arg)),e.output&&t.appendChild(s("span","to",e.output)),t}function Z(e,t){let r=e.userEl;r.innerHTML="";let i=[e.question,...e.attachmentNames.map(x=>f.attachmentLabel(x))].filter(Boolean);r.appendChild(s("div","bub",i.join(`
`)));let c=s("div","acts");if(Ie(e)!==void 0){let x=L("pencil",f.edit,"sm needs-idle");x.addEventListener("click",()=>Rt(t)),c.appendChild(x)}c.appendChild(s("span","time",Gt(e.askedAt))),r.appendChild(c)}function Ie(e){return e.versions[e.versions.length-1]?.turnSeq}function nn(e,t,r){let i=s("div");i.style.display="contents";let c=s("div","u"),a=s("div","a");return i.append(c,a),{question:e,attachmentNames:t,attachments:r,askedAt:new Date,versions:[],shown:0,el:i,userEl:c,answerEl:a,view:At(a)}}function At(e){e.innerHTML="";let t={folds:s("div"),body:s("div","md"),extras:s("div"),acts:s("div","aacts"),feedback:s("div"),followUps:s("div","fups")};return e.append(t.folds,t.body,t.extras,t.acts,t.feedback,t.followUps),t}function ce(e){let t=e.versions[e.shown];e.view=At(e.answerEl);let r=e.view;if(t){if(t.thinkingText){let i=t.thinkingMs!==null?Math.max(1,Math.round(t.thinkingMs/1e3)):null,c=ze(i!==null?f.thoughtFor(i):f.thinking,t.live&&t.thinkingMs===null);c.body.textContent=t.thinkingText,r.folds.appendChild(c.wrap),r.thinkingFold=c}if(t.tools.length){let i=ze(t.live?f.toolsWorking:f.toolsUsed(t.tools.length),t.live);for(let c of t.tools)i.body.appendChild(ot(c));r.folds.appendChild(i.wrap),r.toolsFold=i}t.live?(r.body.className="md raw caret",r.body.textContent=t.text):t.text.trim()&&(r.body.className="md",qt(r.body,t.text,{theme:p()})),t.stopped&&r.body.after(s("div","note",f.stopped)),t.error&&r.body.after(s("div","note error",f.requestFailed(t.error))),t.citations.length&&r.extras.appendChild(gn(t.citations)),t.artifacts.length&&r.extras.appendChild(xn(t.artifacts)),t.live||Fe(e),Mt(e)}}function Fe(e){let t=e.versions[e.shown],r=e.view.acts;if(r.innerHTML="",!t||t.live)return;if(t.text){let a=L("copy",f.copy,"sm");a.addEventListener("click",()=>{rn(t.text)}),r.appendChild(a)}let i=u[u.length-1]===e,c=e.shown===e.versions.length-1;if(i&&c&&t.turnSeq!==void 0){let a=L("redo",f.regenerate,"sm needs-idle");a.addEventListener("click",()=>{ln(e)}),r.appendChild(a)}else if(i&&c&&t.rejected){let a=s("button","btn needs-idle",f.retry);a.type="button",a.addEventListener("click",()=>{cn(e)}),r.appendChild(a)}if(e.versions.length>1){let a=s("span","vn"),x=L("cleft",f.prevVersion,"sm");x.disabled=e.shown===0||k,x.addEventListener("click",()=>_t(e,e.shown-1));let w=L("cright",f.nextVersion,"sm");w.disabled=e.shown===e.versions.length-1||k,w.addEventListener("click",()=>_t(e,e.shown+1)),a.append(x,s("span","",`${e.shown+1}/${e.versions.length}`),w),r.appendChild(a)}if(t.turnId){let a=L("like",f.like,"sm");a.setAttribute("aria-pressed",String(t.rating==="UP")),a.addEventListener("click",()=>{on(e,t)});let x=L("dislike",f.dislike,"sm");x.setAttribute("aria-pressed",String(t.rating==="DOWN"||t.feedbackOpen)),x.addEventListener("click",()=>{an(e,t)}),r.append(a,x)}r.appendChild(s("span","time",Gt(t.at))),t.model&&r.appendChild(s("span","mdl",Ct(t.model)))}function Mt(e){let t=e.view.followUps;t.innerHTML="";let r=e.versions[e.shown],i=u[u.length-1]===e;if(!(!r||!i||e.shown!==e.versions.length-1||k))for(let c of r.followUps){let a=s("button","fup");a.type="button";let x=s("span","ar");x.innerHTML=I("cright",12),a.append(x,s("span","",c)),a.addEventListener("click",()=>{De(c)}),t.appendChild(a)}}function _t(e,t){k||t<0||t>=e.versions.length||(e.shown=t,ce(e))}async function rn(e){if(await Te(e)){de(f.copied);return}de(f.copyFailed),n.on?.onError?.(new Error("clipboard copy failed"))}async function on(e,t){if(t.turnId){e.view.feedback.innerHTML="";try{t.rating==="UP"?(await se.deleteFeedback(t.turnId),t.rating=void 0):(await se.saveFeedback({sessionId:h,turnId:t.turnId,rating:"UP"}),t.rating="UP",t.feedbackOpen=!1)}catch(r){de(f.feedbackFailed),n.on?.onError?.(r)}Fe(e)}}async function an(e,t){if(t.turnId){if(t.rating==="DOWN"){try{await se.deleteFeedback(t.turnId),t.rating=void 0}catch(r){de(f.feedbackFailed),n.on?.onError?.(r)}He(e,t);return}if(t.feedbackOpen){He(e,t);return}sn(e,t)}}function He(e,t){t.feedbackOpen=!1,e.view.feedback.innerHTML="",Fe(e)}function sn(e,t){t.feedbackOpen=!0,Fe(e);let r=s("div","fb");r.appendChild(s("div","fq",f.feedbackQuestion));let i=new Set,c=A?.feedback_reasons??[];if(c.length){let F=s("div","fc");F.setAttribute("aria-label",f.reason);for(let ke of c){let Ne=s("button","chipb",ke.label);Ne.type="button",Ne.addEventListener("click",()=>{i.has(ke.code)?i.delete(ke.code):i.add(ke.code),Ne.classList.toggle("on",i.has(ke.code))}),F.appendChild(Ne)}r.appendChild(F)}let a=s("input");a.type="text",a.placeholder=f.comment,a.setAttribute("aria-label",f.comment);let x=A?.feedback_comment_max_chars??cr,w=s("div","warn",f.commentTooLong(x));w.hidden=!0,r.append(a,w);let _=s("div","eb"),pe=s("button","btn",f.close);pe.type="button",pe.addEventListener("click",()=>He(e,t));let E=s("button","btn pri",f.send);E.type="button",a.addEventListener("input",()=>{let F=a.value.length>x;w.hidden=!F,E.disabled=F}),a.addEventListener("keydown",F=>{F.isComposing||F.keyCode===229||F.key==="Enter"&&!E.disabled&&E.click()}),E.addEventListener("click",async()=>{if(!(!t.turnId||a.value.length>x)){E.disabled=!0;try{await se.saveFeedback({sessionId:h,turnId:t.turnId,rating:"DOWN",reasonCodes:[...i],comment:a.value.trim()||void 0}),t.rating="DOWN",He(e,t),de(f.feedbackSent)}catch(F){E.disabled=!1,de(f.feedbackFailed),n.on?.onError?.(F)}}}),_.append(pe,E),r.appendChild(_),e.view.feedback.innerHTML="",e.view.feedback.appendChild(r),a.focus(),G()}function Rt(e){if(k)return;let t=u[e],r=Ie(t);if(!t||r===void 0)return;let i=t.userEl;i.innerHTML="";let c=s("div","edit"),a=document.createElement("textarea");a.value=t.question,c.appendChild(a),e<u.length-1&&c.appendChild(s("div","warn",f.editWarning));let x=s("div","eb"),w=s("button","btn",f.cancel);w.type="button",w.addEventListener("click",()=>Z(t,e));let _=s("button","btn pri",f.send);_.type="button";let pe=()=>{let E=a.value.trim();!E&&t.attachments.length===0||dn(e,E)};_.addEventListener("click",pe),a.addEventListener("keydown",E=>{E.isComposing||E.keyCode===229||(E.key==="Enter"&&!E.shiftKey?(E.preventDefault(),pe()):E.key==="Escape"&&(E.stopPropagation(),Z(t,e)))}),a.addEventListener("input",()=>le(a)),x.append(w,_),c.appendChild(x),i.appendChild(c),le(a),a.focus(),a.setSelectionRange(a.value.length,a.value.length)}async function dn(e,t){if(k)return;let r=u[e],i=Ie(r);if(i===void 0)return;let c=u.splice(e+1),a={question:r.question,askedAt:r.askedAt,versions:r.versions,shown:r.shown};for(let w of c)w.el.remove();r.question=t,r.askedAt=new Date,r.versions=[],r.shown=0,Z(r,e);let x=await Oe(r,i);if(x.rejected){Object.assign(r,a);for(let w of c)u.push(w),X.appendChild(w.el);u.forEach((w,_)=>{Z(w,_),ce(w)}),K(f.requestFailed(x.error??""))}}async function De(e){if(k)return;let t=e.trim(),r=R.filter(w=>w.status==="ready"&&w.serverMeta);if(!t&&r.length===0)return;let i=r.map(w=>({attachmentId:w.serverMeta.attachment_id,mediaType:w.serverMeta.media_type})),c=r.map(w=>w.file.name);R=[],ve(),ne.remove();let a=u[u.length-1],x=nn(t,c,i);u.push(x),X.appendChild(x.el),Z(x,u.length-1),a&&ce(a),u.length===1&&!me&&t&&tt(t.slice(0,lr)),G(!0),await Oe(x)}async function ln(e){if(k)return;let t=Ie(e);if(t===void 0)return;let r=e.askedAt;e.askedAt=new Date;let i=await Oe(e,t);i.rejected&&(e.versions.pop(),e.shown=e.versions.length-1,e.askedAt=r,Z(e,u.indexOf(e)),ce(e),K(f.requestFailed(i.error??"")))}async function cn(e){k||(e.versions.pop(),e.shown=Math.max(0,e.versions.length-1),e.askedAt=new Date,Z(e,u.indexOf(e)),await Oe(e))}async function Oe(e,t){let r=mr();e.versions.push(r),e.shown=e.versions.length-1,C={turn:e,version:r},nt(!0),ce(e),G();try{await _e.sendMessage({baseUrl:n.baseUrl,sessionId:h,text:e.question,model:B,ragSourceId:n.ragSourceId,connectionId:n.connectionId,actionsEnabled:n.actionsEnabled,context:n.contextProvider?.(),attachments:e.attachments,rewindFromSeq:t,thinking:A?.thinking_supported?Q?"on":"off":void 0},i=>pn(e,r,i))}catch(i){r.rejected=i instanceof ue,r.error=en(i),n.on?.onError?.(i)}finally{$t(r),r.live=!1,C?.version===r&&(C=null),nt(!1),ce(e),Z(e,u.indexOf(e)),G()}return un(e,r),r}function $t(e){e.thinkingStartedAt!==null&&e.thinkingMs===null&&(e.thinkingMs=Date.now()-e.thinkingStartedAt)}function pn(e,t,r){n.on?.onMessage?.(r);let i=e.view;switch(r.type){case"thinking":{if(t.thinkingStartedAt===null&&(t.thinkingStartedAt=Date.now()),t.thinkingText+=r.text??"",!i.thinkingFold){let c=ze(f.thinking,!0);i.folds.prepend(c.wrap),i.thinkingFold=c}i.thinkingFold.body.textContent=t.thinkingText;break}case"delta":{t.thinkingStartedAt!==null&&t.thinkingMs===null&&($t(t),i.thinkingFold&&(i.thinkingFold.btn.classList.remove("live"),i.thinkingFold.label.textContent=f.thoughtFor(Math.max(1,Math.round((t.thinkingMs??0)/1e3))))),t.text+=r.text??"",i.body.textContent=t.text;break}case"tool_use_start":{let c={id:r.tool?.id,name:r.tool?.name??"tool",arg:bn(r.tool?.input),status:"running"};if(t.tools.push(c),!i.toolsFold){let a=ze(f.toolsWorking,!0);i.folds.appendChild(a.wrap),i.toolsFold=a}i.toolsFold.body.appendChild(ot(c));break}case"tool_result":{let c=t.tools.find(a=>a.id&&a.id===r.toolResult?.tool_use_id);if(c&&(c.status=r.toolResult?.is_error?"failed":"done",c.output=hn(r.toolResult?.output)),i.toolsFold){i.toolsFold.body.innerHTML="";for(let a of t.tools)i.toolsFold.body.appendChild(ot(a))}break}case"done":{t.turnId=r.done?.turn_id,t.turnSeq=r.done?.turn_seq,t.model=r.done?.model,t.citations=r.done?.citations??[],t.artifacts=r.done?.artifacts??[],t.at=new Date;break}case"error":{t.error=r.error??"unknown error";break}}G()}async function un(e,t){if(!A?.follow_ups_enabled||!t.turnId||t.stopped||t.error||!t.text.trim())return;let r=h;try{let i=await se.followUps(r,t.turnId,n.connectionId);if(r!==h)return;t.followUps=(i.follow_ups??[]).filter(c=>c&&c.trim()),i.title&&(me=!0,tt(i.title)),u.includes(e)&&(Mt(e),G())}catch{}}async function it(){!k||!C||(C.version.stopped=!0,await _e.stop(n.baseUrl,h))}async function mn(){k&&await _e.abort(n.baseUrl,h);let e=h;h=Jt(),u=[],C=null,me=!1,nt(!1),X.innerHTML="",X.appendChild(ne),H=new Map;for(let t of R)t.serverMeta&&Qe.delete({baseUrl:n.baseUrl,sessionId:e,attachmentId:t.serverMeta.attachment_id}).catch(()=>{});R=[],ve(),tt(f.defaultTitle),S.value="",le(S),oe(),S.focus(),n.on?.onNewConversation?.({previousSessionId:e,sessionId:h})}function fn(e){if(!e)return"tool";let t=e.split("__");return t[t.length-1]||e}function hn(e){let t=(e??"").replace(/\s+/g," ").trim();if(t)return t.length>Xt?`${t.slice(0,Xt)}\u2026`:t}function bn(e){if(!e||typeof e!="object")return"";let t=Object.keys(e);if(!t.length)return"";let r=e[t[0]],i=typeof r=="string"?r:JSON.stringify(r);return i&&i.length>48&&(i=i.slice(0,48)+"\u2026"),i??""}function gn(e){let t=s("div","citations");return e.forEach((r,i)=>{let c=s("button","citation",`[${r.index??i+1}] ${r.document_name??r.source_id}`);c.type="button",c.title=r.content_preview,c.addEventListener("click",()=>{wn(r)}),t.appendChild(c)}),t}function xn(e){let t=s("div","artifacts");return e.forEach(r=>{let i=s("button","artifact");i.type="button",i.innerHTML=I("attach",13),i.appendChild(s("span","",`${r.name} (${yn(r.size)})`)),i.title=`\uB2E4\uC6B4\uB85C\uB4DC \xB7 ${r.name}`,i.addEventListener("click",()=>{vn(r)}),t.appendChild(i)}),t}async function vn(e){try{let t=await W.getToken(),r=await fetch(`${n.baseUrl}${e.download_url}`,{headers:{Authorization:`Bearer ${t}`}});if(!r.ok)throw new Error(`download ${r.status}`);let i=await r.blob(),c=URL.createObjectURL(i),a=document.createElement("a");a.href=c,a.download=e.name,a.click(),setTimeout(()=>URL.revokeObjectURL(c),1e4)}catch(t){K(`\uB2E4\uC6B4\uB85C\uB4DC \uC2E4\uD328: ${t.message}`)}}function yn(e){return!Number.isFinite(e)||e<0?"":e<1024?`${e}B`:e<1024*1024?`${(e/1024).toFixed(0)}KB`:`${(e/(1024*1024)).toFixed(1)}MB`}async function wn(e){T.querySelector(".citation-preview")?.remove();let t=s("div","citation-preview"),r=L("x",f.close,"close");r.addEventListener("click",()=>t.remove()),t.appendChild(r),t.appendChild(s("h4","",e.document_name??e.source_id)),t.appendChild(s("div","meta",`score ${e.score.toFixed(2)}${e.page_number?` \xB7 page ${e.page_number}`:""}`));let i=s("div","content",e.content_preview);if(t.appendChild(i),T.appendChild(t),e.chunk_id)try{let c=await St.fetchChunk(n.baseUrl,e.source_id,e.chunk_id);typeof c.content=="string"&&(i.textContent=c.content)}catch{}}function kn(){return`att-${Date.now()}-${Math.random().toString(36).slice(2,8)}`}function Tn(e){let t=e.type.startsWith("image/"),r=e.type==="application/pdf";if(!t&&!r)return"\uC774\uBBF8\uC9C0(PNG/JPEG/GIF/WEBP) \uB610\uB294 PDF \uB9CC \uCCA8\uBD80 \uAC00\uB2A5\uD569\uB2C8\uB2E4";let i=r?ir:or;return e.size>i?`\uD30C\uC77C\uC774 \uB108\uBB34 \uD07D\uB2C8\uB2E4 (\uCD5C\uB300 ${Math.round(i/1024/1024)}MB)`:null}function ve(){Ze.innerHTML="";for(let e of R){let t=s("div",`chip ${e.status}`);if(e.previewDataUrl){let a=document.createElement("img");a.className="chip-thumb",a.src=e.previewDataUrl,a.alt=e.file.name,t.appendChild(a)}else t.appendChild(s("span","chip-thumb pdf","PDF"));let r=s("span","chip-label"),i=e.serverMeta?.pages,c=i?` \xB7 ${i}p`:"";if(r.textContent=e.status==="error"?`${e.file.name} \u2014 ${e.error??"\uC2E4\uD328"}`:`${e.file.name}${c}`,r.title=r.textContent??"",t.appendChild(r),e.status==="uploading")t.appendChild(s("span","chip-spinner"));else{let a=s("button","chip-remove","\xD7");a.type="button",a.setAttribute("aria-label",f.removeAttachment),a.addEventListener("click",()=>{Sn(e.localId)}),t.appendChild(a)}Ze.appendChild(t)}oe()}async function En(e){if(e.type.startsWith("image/"))return new Promise(t=>{let r=new FileReader;r.onload=()=>t(typeof r.result=="string"?r.result:void 0),r.onerror=()=>t(void 0),r.readAsDataURL(e)})}async function at(e){let t=Tn(e);if(t){n.on?.onError?.(new Error(t)),K(t);return}let r={localId:kn(),file:e,status:"uploading",previewDataUrl:await En(e)};R.push(r),ve();let i=h;try{let c=await Qe.upload({baseUrl:n.baseUrl,sessionId:i,file:e});r.serverMeta=c,r.status="ready"}catch(c){r.status="error",r.error=c.message,n.on?.onError?.(c)}finally{ve()}}async function Sn(e){let t=R.findIndex(i=>i.localId===e);if(t<0)return;let r=R[t];if(R.splice(t,1),ve(),r.serverMeta)try{await Qe.delete({baseUrl:n.baseUrl,sessionId:h,attachmentId:r.serverMeta.attachment_id})}catch{}}Tt.addEventListener("click",()=>O.click()),O.addEventListener("change",()=>{let e=O.files?Array.from(O.files):[];for(let t of e)at(t);O.value=""});let ye=0,ie=null;function Pe(e){return!!e.dataTransfer&&Array.from(e.dataTransfer.types??[]).includes("Files")}T.addEventListener("dragenter",e=>{Pe(e)&&(e.preventDefault(),ye+=1,ie||(ie=s("div","drop-overlay",f.dropHere),T.appendChild(ie)))}),T.addEventListener("dragover",e=>{Pe(e)&&e.preventDefault()}),T.addEventListener("dragleave",e=>{Pe(e)&&(ye-=1,ye<=0&&(ye=0,ie?.remove(),ie=null))}),T.addEventListener("drop",e=>{if(!Pe(e))return;e.preventDefault(),ye=0,ie?.remove(),ie=null;let t=e.dataTransfer?Array.from(e.dataTransfer.files):[];for(let r of t)at(r)});let Ln=new Ee(W),z=null,we=[],Ue=null,zt=0,je=null,Be=null,ae="idle",Cn=6e4;function It(){return typeof window<"u"&&!!window.isSecureContext&&!!navigator.mediaDevices?.getUserMedia&&typeof MediaRecorder<"u"}function An(){for(let e of["audio/webm;codecs=opus","audio/webm","audio/mp4"])try{if(MediaRecorder.isTypeSupported(e))return e}catch{}}function Y(e){ae=e;let t=e==="recording";P.setAttribute("aria-pressed",String(t)),P.setAttribute("aria-label",t?"\uB179\uC74C \uC815\uC9C0":"\uC74C\uC131 \uC785\uB825 \uC2DC\uC791"),P.setAttribute("data-tip",t?"\uB179\uC74C \uC815\uC9C0":"\uC74C\uC131 \uC785\uB825 \uC2DC\uC791"),P.innerHTML=I(t?"stop":"mic"),P.classList.toggle("rec-on",t),ge.classList.toggle("hidden",!t)}function Mn(e){let t=Math.floor(e/1e3);return`${Math.floor(t/60)}:${(t%60).toString().padStart(2,"0")}`}function Ft(){je&&(clearInterval(je),je=null),Be&&(clearTimeout(Be),Be=null)}function st(){Ue?.getTracks().forEach(e=>e.stop()),Ue=null}async function _n(){if(ae!=="idle")return;if(!It()){K("\uC774 \uBE0C\uB77C\uC6B0\uC800\xB7\uD658\uACBD\uC5D0\uC11C\uB294 \uC74C\uC131 \uC785\uB825\uC744 \uC0AC\uC6A9\uD560 \uC218 \uC5C6\uC2B5\uB2C8\uB2E4 (HTTPS \uD544\uC218)");return}Y("requesting-permission");try{Ue=await navigator.mediaDevices.getUserMedia({audio:!0})}catch(t){K("\uB9C8\uC774\uD06C \uAD8C\uD55C\uC774 \uD544\uC694\uD569\uB2C8\uB2E4. \uBE0C\uB77C\uC6B0\uC800 \uC8FC\uC18C\uCC3D\uC758 \uC790\uBB3C\uC1E0 \uC544\uC774\uCF58\uC744 \uB20C\uB7EC \uD5C8\uC6A9\uD574\uC8FC\uC138\uC694"),n.on?.onError?.(t),Y("idle");return}let e=An();try{z=new MediaRecorder(Ue,e?{mimeType:e}:void 0)}catch(t){st(),Y("idle"),K("\uC774 \uBE0C\uB77C\uC6B0\uC800\uC758 MediaRecorder \uD3EC\uB9F7\uC774 \uC11C\uBC84\uC640 \uD638\uD658\uB418\uC9C0 \uC54A\uC2B5\uB2C8\uB2E4"),n.on?.onError?.(t);return}we=[],z.ondataavailable=t=>{t.data.size>0&&we.push(t.data)},z.onstop=()=>{Rn()},z.onerror=t=>{n.on?.onError?.(new Error("recorder error: "+t.type))},z.start(),zt=Date.now(),Y("recording"),Ye.textContent="0:00",je=setInterval(()=>{Ye.textContent=Mn(Date.now()-zt)},250),Be=setTimeout(()=>{ae==="recording"&&dt()},Cn)}function dt(){if(ae==="recording"){Ft();try{z?.stop()}catch{}Y("uploading")}}function lt(){if(Ft(),z&&z.state!=="inactive"){z.onstop=null;try{z.stop()}catch{}}st(),z=null,we=[],Y("idle")}async function Rn(){let e=we;if(we=[],st(),!e.length){Y("idle");return}let t=z?.mimeType||"audio/webm";z=null;let r=new Blob(e,{type:t});try{let i=await Ln.transcribe({baseUrl:n.baseUrl,sessionId:h,blob:r});zn(S,i.text),S.focus(),le(S),oe()}catch(i){let c=i instanceof j?i:new Error(String(i));K($n(c)),n.on?.onError?.(c)}finally{Y("idle")}}function $n(e){if(e instanceof j)switch(e.code){case"STT_RATE_LIMITED":return"\uC74C\uC131 \uC785\uB825\uC744 \uB108\uBB34 \uC790\uC8FC \uC0AC\uC6A9\uD588\uC2B5\uB2C8\uB2E4. \uC7A0\uC2DC \uD6C4 \uB2E4\uC2DC \uC2DC\uB3C4\uD574\uC8FC\uC138\uC694";case"STT_FILE_TOO_LARGE":return"\uB179\uC74C \uD30C\uC77C\uC774 \uB108\uBB34 \uD07D\uB2C8\uB2E4";case"STT_FILE_TOO_LONG":return"\uB179\uC74C \uC2DC\uAC04\uC774 \uB108\uBB34 \uAE41\uB2C8\uB2E4";case"STT_INVALID_MIME":return"\uC774 \uC624\uB514\uC624 \uD3EC\uB9F7\uC740 \uC11C\uBC84\uC5D0\uC11C \uC9C0\uC6D0\uD558\uC9C0 \uC54A\uC2B5\uB2C8\uB2E4";case"STT_PROVIDER_UNAVAILABLE":return"\uC74C\uC131 \uC778\uC2DD \uC11C\uBE44\uC2A4\uB97C \uC0AC\uC6A9\uD560 \uC218 \uC5C6\uC2B5\uB2C8\uB2E4 (OpenAI \uC5F0\uACB0 \uD655\uC778 \uD544\uC694)";case"STT_TIMEOUT":return"\uC74C\uC131 \uC778\uC2DD \uC751\uB2F5\uC774 \uC9C0\uC5F0\uB429\uB2C8\uB2E4. \uB2E4\uC2DC \uC2DC\uB3C4\uD574\uC8FC\uC138\uC694";case"STT_NETWORK":return"\uB124\uD2B8\uC6CC\uD06C \uC624\uB958\uB85C \uC74C\uC131\uC744 \uC804\uC1A1\uD558\uC9C0 \uBABB\uD588\uC2B5\uB2C8\uB2E4"}return"\uC74C\uC131 \uC778\uC2DD\uC5D0 \uC2E4\uD328\uD588\uC2B5\uB2C8\uB2E4: "+e.message}function zn(e,t){let r=e.selectionStart??e.value.length,i=e.selectionEnd??e.value.length,c=e.value.slice(0,r),a=e.value.slice(i),w=(c.length>0&&!/\s$/.test(c)?" ":"")+t;e.value=c+w+a;let _=(c+w).length;e.setSelectionRange(_,_)}P.addEventListener("click",()=>{ae==="idle"?_n():ae==="recording"&&dt()}),Me.addEventListener("click",()=>dt()),Ae.addEventListener("click",()=>lt());function Ht(){if(k)return;let e=S.value;De(e),S.value="",le(S),oe()}D.addEventListener("click",()=>{k?it():Ht()}),S.addEventListener("keydown",e=>{e.isComposing||e.keyCode===229||(e.key==="Enter"&&!e.shiftKey?(e.preventDefault(),Ht()):e.key==="ArrowUp"&&!S.value&&!k&&u.length&&(e.preventDefault(),Rt(u.length-1)))}),S.addEventListener("input",()=>{le(S),oe()}),S.addEventListener("paste",e=>{let t=e.clipboardData?.items;if(t){for(let r of t)if(r.kind==="file"){let i=r.getAsFile();i&&at(i)}}}),T.addEventListener("keydown",e=>{e.key==="Escape"&&(ae==="recording"?(e.stopPropagation(),lt()):k?(e.stopPropagation(),it()):xe()?e.stopPropagation():fe&&(e.stopPropagation(),$e(!1)))}),Lt(pr()??(mt.includes(n.fontSize)?n.fontSize:"medium")),oe(),rt(),(async()=>{try{await W.ensureLoaded(),It()&&W.hasScope("chat:stt")&&(P.hidden=!1)}catch{}await tn()})();async function In(e){let t=s("div","workflow-panel"),r=s("div","title",`\uC6CC\uD06C\uD50C\uB85C\uC6B0 ${e.slice(0,8)}\u2026`);t.appendChild(r);let i=s("div","steps");t.appendChild(i),ne.remove(),X.appendChild(t);let c=new Map;return H.set(e,{stepEl:t,steps:c}),St.subscribe(n.baseUrl,e,{onSnapshot:a=>{r.textContent=`\uC6CC\uD06C\uD50C\uB85C\uC6B0 ${a.workflow_id??e.slice(0,8)} \u2014 ${a.status}`},onStep:a=>{n.on?.onWorkflowStep?.(a);let x=c.get(a.step_id);x||(x=s("div","workflow-step"),c.set(a.step_id,x),i.appendChild(x));let w=a.status==="running"?"running":a.status==="completed"?"completed":"failed";if(x.innerHTML="",x.appendChild(s("span",`dot ${w}`)),x.appendChild(s("span","label",`${a.step_id}${a.duration_ms?` \xB7 ${a.duration_ms}ms`:""}`)),a.sub_workflow_id){let _=s("span","sub",` \u2192 ${a.sub_workflow_id}`);_.style.color="var(--aimbase-muted)",x.appendChild(_)}},onApproval:a=>{n.on?.onApprovalRequired?.(a);let x=s("div","workflow-step");x.textContent=`\uC2B9\uC778 \uB300\uAE30: ${a.step_id} \u2014 ${a.reason??a.policy_id}`,i.appendChild(x)},onDone:a=>{r.textContent=`\uC6CC\uD06C\uD50C\uB85C\uC6B0 ${e.slice(0,8)}\u2026 \u2014 ${a.status} (${a.duration_ms}ms)`,H.delete(e)},onError:a=>{n.on?.onError?.(a)}})}return{open:()=>Re(!0),close:()=>Re(!1),sendMessage:De,abort:()=>it(),subscribeWorkflow:e=>{let t=null;return In(e).then(r=>t=r),()=>t?.()},destroy:()=>{lt(),k&&_e.abort(n.baseUrl,h),W.destroy(),d.remove()}}}function fr(n){return n==="bubble"?"floating":n==="panel"?"side":"inline"}var hr=["small","medium","large"];function br(n){if(n)try{let o=JSON.parse(n);return Array.isArray(o)?o.filter(l=>typeof l=="string"):void 0}catch{console.error("[aimbase-chat] suggestions attribute must be a JSON array of strings");return}}var Ge=class extends HTMLElement{constructor(){super(...arguments);this.handle=null;this.authResolver=null;this.contextProvider=null;this.ragSourceId=null}static get observedAttributes(){return["base-url","token-endpoint","display","theme-mode","rag-source-id","session-id","connection-id","actions-enabled","model","suggestions","empty-title","empty-subtitle","font-size"]}connectedCallback(){let l=this.getAttribute("base-url");if(!l){console.error("[aimbase-chat] base-url attribute is required");return}let d=this.getAttribute("token-endpoint"),p=this.authResolver??(d?async()=>{let h=await fetch(d,{method:"POST",credentials:"include"});if(!h.ok)throw new Error(`token endpoint ${h.status}`);let u=await h.json();return u.data??u}:null);if(!p){console.error("[aimbase-chat] token-endpoint attribute or authResolver property is required");return}let g=this.getAttribute("display")??"inline",b=this.getAttribute("theme-mode"),y=this.getAttribute("actions-enabled"),m=this.getAttribute("font-size"),v={baseUrl:l,authResolver:p,display:g,sessionId:this.getAttribute("session-id")??void 0,ragSourceId:this.ragSourceId??this.getAttribute("rag-source-id")??void 0,connectionId:this.getAttribute("connection-id")??void 0,actionsEnabled:y==="true"||y===""?!0:void 0,model:this.getAttribute("model")??void 0,target:g==="inline"?this:void 0,theme:b?{mode:b}:void 0,contextProvider:this.contextProvider??void 0,suggestions:br(this.getAttribute("suggestions")),emptyTitle:this.getAttribute("empty-title")??void 0,emptySubtitle:this.getAttribute("empty-subtitle")??void 0,fontSize:m&&hr.includes(m)?m:void 0,on:{onNewConversation:h=>this.dispatchEvent(new CustomEvent("new-conversation",{detail:h,bubbles:!0,composed:!0}))}};this.handle=Se(v)}disconnectedCallback(){this.handle?.destroy(),this.handle=null}subscribeWorkflow(l){return this.handle?.subscribeWorkflow(l)??(()=>{})}open(){this.handle?.open()}close(){this.handle?.close()}};function ft(){typeof customElements>"u"||customElements.get("aimbase-chat")||customElements.define("aimbase-chat",Ge)}if(typeof window<"u")try{ft()}catch{}function Yr(n){return Se(n)}export{Ge as AimbaseChatElement,Ee as SttClient,j as SttError,Se as createWidget,ft as defineAimbaseChat,Yr as init};
//# sourceMappingURL=aimbase-chat.esm.js.map