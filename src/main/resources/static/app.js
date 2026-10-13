const $ = (s, el = document) => el.querySelector(s);
const esc = v => String(v ?? '').replace(/[&<>"']/g, x => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[x]));
const KEY = 'interview-studio-v1';
const defaults = { role: '', resume: '', jd: '', style: 'coach', type: 'behavioral', language: 'zh', rounds: 6 };
let state = { config: { ...defaults }, current: null, records: [], prep: null };
let storageError = false;
try { const saved = JSON.parse(localStorage.getItem(KEY)); if (saved && saved.config && Array.isArray(saved.records)) state = { ...state, ...saved }; } catch { storageError = true; }
let page = state.current ? 'interview' : 'setup', busy = false, configured = false, model = '', toastTimer;
function save() { try { localStorage.setItem(KEY, JSON.stringify(state)); } catch { toast('浏览器无法保存记录。请导出当前复盘，避免刷新后丢失。'); } }
function toast(text) { const el = $('#toast'); el.textContent = text; el.hidden = false; clearTimeout(toastTimer); toastTimer = setTimeout(() => el.hidden = true, 5500); }
function shell() {
  document.querySelectorAll('[data-nav]').forEach(b => b.classList.toggle('active', b.dataset.nav === page));
  $('#breadcrumb').textContent = `工作台 / ${{setup:'面试准备',interview:'模拟面试',history:'练习记录',settings:'模型与数据',report:'面试复盘'}[page]}`;
  $('#live-dot').hidden = !state.current;
  $('#connection').textContent = configured ? '● AI 已配置' : '○ AI 尚未配置';
  $('#connection').classList.toggle('warning', !configured);
}
function navigate(next) { if (busy) return toast('正在处理，请稍等。'); page = next; render(); window.scrollTo(0,0); }
document.querySelectorAll('[data-nav]').forEach(b => b.addEventListener('click', () => navigate(b.dataset.nav)));
function lock(value) { busy = value; document.querySelectorAll('button').forEach(b => b.disabled = value); if (!value && page === 'setup') updateAIButtons(); if (!value && page === 'interview' && $('#finish')) $('#finish').disabled = !state.current?.history.some(h => h.role === 'candidate'); }
function updateAIButtons() { for (const id of ['start','prepare']) if ($('#'+id)) $('#'+id).disabled = !configured || busy; }
async function api(action, config, history = []) {
  const csrfResponse = await fetch('/api/csrf');
  if (!csrfResponse.ok) throw new Error('无法获取登录状态，请刷新页面。');
  const csrf = await csrfResponse.json();
  const response = await fetch('/api/agent', { method:'POST', headers:{'Content-Type':'application/json',[csrf.headerName]:csrf.token}, body:JSON.stringify({action,config,history}), signal:AbortSignal.timeout(75000) });
  if (response.status === 401) throw new Error('登录已过期，输入已保留；请刷新页面重新登录。');
  const data = await response.json(); if (!response.ok) throw new Error(data.error || '请求失败，请重试。'); return data.result;
}
function errorText(error) { return error.name === 'TimeoutError' ? '请求超时，输入已保留，请重试。' : error.message || '连接失败，输入已保留，请重试。'; }
function configFromForm() { const f = new FormData($('#setup-form')); return Object.fromEntries([...f].map(([k,v]) => [k,k==='rounds'?Number(v):v])); }
function heading(eyebrow, title, sub) { return `<section class="heading"><div><div class="eyebrow">${eyebrow}</div><h1>${title}</h1><p>${sub}</p></div></section>`; }
function render() { shell(); if(page==='setup') renderSetup(); if(page==='interview') renderInterview(); if(page==='history') renderHistory(); if(page==='settings') renderSettings(); }
function renderSetup() {
  $('#main').replaceChildren($('#setup-template').content.cloneNode(true));
  const form = $('#setup-form');
  for (const [k,v] of Object.entries(state.config)) { if (k === 'style') { const input = form.querySelector(`input[name=style][value="${v==='realistic'?'realistic':'coach'}"]`); input.checked = true; } else if (form.elements[k]) form.elements[k].value = v; }
  const update = () => { state.config = configFromForm(); $('#resume-count').textContent = `${state.config.resume.length.toLocaleString()} / 18,000`; save(); };
  form.addEventListener('input', update); form.addEventListener('change', update); update(); updateAIButtons();
  if(!configured) { $('#setup-error').hidden = false; $('#setup-error').textContent = '尚未连接模型。可以先体验流程演示，或在「模型与数据」查看云端配置方法。'; }
  $('#resume-file').addEventListener('change', async event => {
    const file = event.target.files[0]; if(!file) return;
    if(!/\.(txt|md)$/i.test(file.name) || file.size > 100000) return toast('请选择 100 KB 以内的 TXT 或 Markdown 文本文件。');
    const text = await file.text(); if(text.length > 18000) return toast('材料超过 18,000 字符，请精简后导入。');
    $('#resume').value = text; update(); toast('经历材料已导入。');
  });
  const validate = () => { if(!form.reportValidity()) return false; if(state.config.type!=='behavioral'&&!state.config.jd.trim()){ $('#setup-error').hidden=false;$('#setup-error').textContent='请填写目标 JD，或选择行为与项目经历。';return false;}return true; };
  $('#prepare').addEventListener('click', async () => {
    update(); if(!validate()) return; lock(true); $('#prep-result').innerHTML='<div class="loading">正在分析岗位与经历…</div>';
    try { const snapshot={...state.config}; const result = await api('prepare',snapshot); state.prep={result,config:JSON.stringify(snapshot)};save();renderPrep(); }
    catch(e){ $('#prep-result').innerHTML=`<div class="error" role="alert">${esc(errorText(e))}</div>`; }
    finally{lock(false);}
  });
  form.addEventListener('submit', async event => { event.preventDefault(); update(); if(validate()) await startSession(false); });
  $('#demo').addEventListener('click', () => { update(); startSession(true); });
  renderPrep();
}
function renderPrep() {
  if(!state.prep || !$('#prep-result')) return;
  const p=state.prep.result;
  $('#prep-result').innerHTML=`<div class="panel content-panel"><div class="eyebrow">YOUR PREPARATION MAP</div><h2>本次准备清单</h2>${state.prep.config!==JSON.stringify(state.config)?'<div class="notice">这份清单基于之前的设置；更新材料后可重新生成。</div>':''}<p>${esc(p.summary)}</p><div class="prep-topics">${p.topics.map(t=>`<article class="prep-topic"><h3>${esc(t.title)}</h3><p>${esc(t.reason)}</p><p><b>准备动作</b><br>${esc(t.preparation)}</p></article>`).join('')}</div><h3>需要补充的素材</h3><ul>${p.missing.map(m=>`<li>${esc(m)}</li>`).join('')}</ul></div>`;
}
const demoQuestions = {
  zh: ['请介绍一个最能体现你个人贡献的项目。','在这个项目里，哪一项关键决策是由你亲自作出的？','你当时为什么选择这个方案？','你用什么证据判断这个项目达到了目标？','如果重新做一次，你会首先改变什么？','请讲一次你与合作方解决分歧的经历。','在那次分歧中，你具体采取了什么行动？','你从这段经历中学到的最重要的一点是什么？'],
  en: ['Tell me about a project that best demonstrates your personal contribution.','Which key decision in that project did you personally make?','Why did you choose that approach?','What evidence showed that the project met its goal?','What would you change first if you did it again?','Tell me about a disagreement you resolved with a stakeholder.','What specific action did you take in that disagreement?','What was the most important lesson you learned?']
};
function demoTurn(s) { const n=s.history.filter(h=>h.role==='candidate').length; return {question:demoQuestions[s.config.language][n%8],focus:s.config.language==='en'?'Example question':'示例问题',kind:n?'followup':'opening',feedback:{evidence:'',strength:'',gap:'',nextStep:''}}; }
async function startSession(demo) {
  if(busy) return;
  if(state.current && !confirm('已有一场未结束的练习。开始新面试会替换它，是否继续？')) return;
  const config={...state.config,role:state.config.role.trim()||(demo?'体验岗位':'')};
  const next={id:crypto.randomUUID(),config,demo,history:[],turns:[],startedAt:new Date().toISOString(),draft:''};
  lock(true);
  try { const turn=demo?demoTurn(next):await api('turn',config,[]); next.history.push({role:'interviewer',text:turn.question});next.turns.push(turn);state.current=next;save();page='interview';render();window.scrollTo(0,0); }
  catch(e) { $('#setup-error').hidden=false;$('#setup-error').textContent=errorText(e); }
  finally{lock(false);}
}
function renderInterview() {
  const s=state.current;
  if(!s) { $('#main').innerHTML=heading('ONE QUESTION AT A TIME','留一点时间，练习表达。','每次只回答一个问题，让思路更清晰。')+'<div class="panel empty"><span>◌</span><h2>还没有进行中的面试</h2><p>先选定岗位、素材和练习方式。</p><button id="go-setup" class="button primary">准备一场面试 →</button></div>';$('#go-setup').onclick=()=>navigate('setup');return; }
  const answered=s.history.filter(h=>h.role==='candidate').length, lastTurn=s.turns.at(-1), ended=answered>=s.config.rounds;
  $('#main').innerHTML=heading('A SPACE TO FIND YOUR WORDS',esc(s.config.role),'不必追求完美答案。先把真实的经历讲清楚。')+
    (s.demo?'<div class="notice">流程演示：以下为固定示例问题，不调用 AI，不会根据回答追问或评价能力。真实 AI 模式需要配置模型。</div>':'')+
    `<div class="interview-grid"><section class="panel"><div class="chat" id="chat" role="log" aria-label="面试对话">${s.history.map((h,i)=>{
      const t=h.role==='interviewer'?s.turns[Math.floor(i/2)]:null;
      return `${t?.feedback?.evidence?`<div class="feedback"><b>上一轮 · 教练反馈</b><p>你的原话：${esc(t.feedback.evidence)}</p><p>亮点：${esc(t.feedback.strength)}</p><p>待改进：${esc(t.feedback.gap)}</p><p>下次试试：${esc(t.feedback.nextStep)}</p></div>`:''}<article class="message ${h.role==='candidate'?'user':'assistant'}"><div class="meta">${h.role==='candidate'?'你的回答':`${s.demo?'示例面试官':'面试官'} · ${esc(t?.focus||'')}`}</div><p>${esc(h.text)}</p></article>`;
    }).join('')}</div><form id="answer-form" class="composer"><label for="answer">${ended?'本轮问答已完成':'你的回答'}</label><textarea id="answer" maxlength="6000" rows="4" ${ended?'disabled':''} placeholder="从背景、你的行动、结果开始讲述…">${esc(s.draft)}</textarea><div class="composer-bottom"><span id="answer-hint">${ended?'点击生成复盘，查看本次练习记录。':'Ctrl / ⌘ + Enter 提交 · '+(s.draft?.length||0)+' / 6,000'}</span><button id="submit-answer" class="button primary" type="${ended?'button':'submit'}">${ended?'生成复盘 ↗':'提交回答 →'}</button></div><div id="interview-error" class="error" role="alert" hidden></div></form></section>
    <aside class="panel content-panel session-info"><div class="eyebrow">SESSION IN PROGRESS</div><div class="question-number">${answered}<small> / ${s.config.rounds} 轮已回答</small></div><div class="progress-track"><div class="progress-fill" id="progress"></div></div><h3>${esc(lastTurn?.focus||'练习进度')}</h3><dl><dt>练习方式</dt><dd>${s.config.style==='coach'?'逐题教练':'连续追问'}</dd><dt>面试语言</dt><dd>${s.config.language==='zh'?'中文':'English'}</dd><dt>目标岗位</dt><dd>${esc(s.config.role)}</dd></dl><p>${s.config.style==='coach'?'每次回答后给出反馈，并根据需要继续追问。最后一轮反馈包含在复盘中。':'过程中保持面试节奏，不展示提示或评价。结束后统一复盘。'}</p><button id="finish" class="button secondary" ${answered?'':'disabled'}>提前结束并复盘</button><p>记录自动保存在当前浏览器。刷新页面可继续练习。</p></aside></div>`;
  $('#progress').style.width=`${Math.min(100,answered/s.config.rounds*100)}%`;
  $('#answer').addEventListener('input',()=>{s.draft=$('#answer').value;$('#answer-hint').textContent=`Ctrl / ⌘ + Enter 提交 · ${s.draft.length} / 6,000`;save();});
  $('#answer-form').onsubmit=submitAnswer;
  $('#answer').addEventListener('keydown',e=>{if(e.key==='Enter'&&(e.metaKey||e.ctrlKey)&&!e.isComposing&&!busy){e.preventDefault();$('#answer-form').requestSubmit();}});
  $('#finish').onclick=finishSession;
  if(ended)$('#submit-answer').onclick=finishSession;
  $('#chat').scrollTop=$('#chat').scrollHeight;
}
async function submitAnswer(event) {
  event.preventDefault();if(busy)return;
  const s=state.current,text=$('#answer').value.trim();if(!text)return toast('先写下你的回答，再继续。');
  const history=[...s.history,{role:'candidate',text}];const count=history.filter(h=>h.role==='candidate').length;
  lock(true);$('#interview-error').hidden=true;$('#submit-answer').textContent='正在处理…';$('#answer').disabled=true;
  try {
    if(count<s.config.rounds){ const turn=s.demo?demoTurn({...s,history}):await api('turn',s.config,history);s.history=[...history,{role:'interviewer',text:turn.question}];s.turns.push(turn); }
    else s.history=history;
    s.draft='';save();renderInterview();
  } catch(e) { $('#interview-error').hidden=false;$('#interview-error').textContent=errorText(e);$('#submit-answer').textContent='重试提交 →';$('#answer').disabled=false; }
  finally {lock(false);if($('#finish'))$('#finish').disabled=!s.history.some(h=>h.role==='candidate');}
}
function demoReview(s) {
  return {summary:'这是一份流程演示复盘。系统未使用 AI 分析你的回答，也未作能力评价。你可以导出本次对话；接入模型后，正式复盘会引用你的回答给出具体建议。',dimensions:[{name:'表达结构',level:'未观察',evidence:'演示模式不评估回答。',advice:'正式练习将根据回答中的背景、行动和结果提供建议。'},{name:'个人贡献',level:'未观察',evidence:'演示模式不评估回答。',advice:'正式练习会区分个人行动与团队成果。'}],strengths:[],gaps:[],improvedAnswer:'接入模型后，将在此展示基于真实回答的改写。缺少的事实会标记为 [待补充]。',practice:['准备一个你亲自作出关键决策的项目。','整理结果依据，缺少的数字先留空。','配置模型后，重新进行一场 AI 面试。']};
}
async function finishSession() {
  if(busy)return;const s=state.current;if(!s.history.some(h=>h.role==='candidate'))return toast('至少回答一个问题再复盘。');
  if(s.draft?.trim()&&!confirm('当前输入框里还有未提交的回答。复盘只分析已提交内容，是否继续？'))return;
  lock(true);$('#interview-error').hidden=true;$('#finish').textContent='正在整理复盘…';
  try { const report=s.demo?demoReview(s):await api('review',s.config,s.history);const record={...s,report,finishedAt:new Date().toISOString()};state.records.unshift(record);state.current=null;save();page='report';renderReport(record); }
  catch(e){$('#interview-error').hidden=false;$('#interview-error').textContent=errorText(e);$('#finish').textContent='重试生成复盘';}
  finally{lock(false);}
}
const items = list => list.length?`<ul>${list.map(t=>`<li>${esc(t)}</li>`).join('')}</ul>`:'<p>本次没有可据此作出的评价。</p>';
function renderReport(s) {
  page='report';shell();const r=s.report;
  $('#main').innerHTML=`<section class="heading"><div><div class="eyebrow">REFLECT. REFINE. REPEAT.</div><h1>每一次练习，都有下一步。</h1><p>${esc(s.config.role)} · ${s.history.filter(h=>h.role==='candidate').length} 轮回答 · ${s.demo?'流程演示':'AI 面试复盘'}</p></div><div class="report-toolbar"><button class="button secondary" id="export">导出复盘 ↓</button><button class="button primary" id="again">再练一次 ↗</button></div></section>${s.demo?'<div class="notice">演示记录，不包含 AI 分析或能力评分。</div>':''}<div class="panel content-panel"><div class="eyebrow">SESSION REFLECTION</div><p class="report-summary">${esc(r.summary)}</p><span class="muted">仅反映本次练习中可观察到的表现，不代表真实招聘结论。</span></div><div class="report-grid"><section class="panel wide"><h3>能力维度与回答证据</h3>${r.dimensions.map(d=>`<div class="dimension"><div class="dimension-head"><b>${esc(d.name)}</b><span class="badge">${esc(d.level)}</span></div><p class="quote">${esc(d.evidence)}</p><p>${esc(d.advice)}</p></div>`).join('')}</section><section class="panel"><h3>值得保留的表达</h3>${items(r.strengths)}</section><section class="panel"><h3>下一次重点改进</h3>${items(r.gaps)}</section><section class="panel wide"><h3>把一段回答讲得更清楚</h3><p>${esc(r.improvedAnswer)}</p></section><section class="panel wide"><h3>下一次练习清单</h3><ol>${r.practice.map(p=>`<li>${esc(p)}</li>`).join('')}</ol></section><section class="panel wide"><details><summary>查看完整对话 · ${s.history.length} 条</summary>${s.history.map(h=>`<p><b>${h.role==='candidate'?'你':'面试官'}</b><br>${esc(h.text)}</p>`).join('')}</details></section></div>`;
  $('#export').onclick=()=>downloadRecord(s);$('#again').onclick=()=>{state.config={...s.config};save();navigate('setup');};window.scrollTo(0,0);
}
function downloadRecord(s) {
  const r=s.report;const text=`# ${s.config.role} · 面试练习复盘\n\n时间：${s.finishedAt}\n模式：${s.demo?'流程演示（未调用 AI）':'AI 面试'}\n\n${r.summary}\n\n## 能力维度\n\n${r.dimensions.map(d=>`### ${d.name} · ${d.level}\n\n证据：${d.evidence}\n\n建议：${d.advice}`).join('\n\n')}\n\n## 亮点\n${r.strengths.map(x=>'- '+x).join('\n')}\n\n## 改进点\n${r.gaps.map(x=>'- '+x).join('\n')}\n\n## 回答改写\n\n${r.improvedAnswer}\n\n## 下一步\n${r.practice.map(x=>'- '+x).join('\n')}\n\n## 对话记录\n\n${s.history.map(h=>`**${h.role==='candidate'?'你':'面试官'}**\n\n${h.text}`).join('\n\n')}\n`;
  const url=URL.createObjectURL(new Blob([text],{type:'text/markdown;charset=utf-8'}));const a=document.createElement('a');a.href=url;a.download=`面试复盘_${s.finishedAt.slice(0,10)}_${s.id.slice(0,6)}.md`;a.click();setTimeout(()=>URL.revokeObjectURL(url),1000);
}
function renderHistory() {
  $('#main').innerHTML=heading('SMALL STEPS, VISIBLE PROGRESS','练习过的每一步，都在这里。','回看回答与反馈，带着一个具体目标开始下一次。')+(state.records.length?state.records.map(s=>`<article class="panel history-item"><div><h3>${esc(s.config.role)} <span class="context-chip">${s.demo?'流程演示':'AI 面试'}</span></h3><p>${esc(new Date(s.finishedAt).toLocaleString('zh-CN'))} · ${s.history.filter(h=>h.role==='candidate').length} 轮 · ${s.config.style==='coach'?'逐题教练':'连续追问'}</p></div><div class="history-buttons"><button class="button secondary" data-view="${esc(s.id)}">查看复盘 →</button><button class="text-button" data-delete="${esc(s.id)}">删除</button></div></article>`).join(''):'<div class="panel empty"><span>▤</span><h2>你的练习记录还在路上</h2><p>完成一场面试并生成复盘后，记录会出现在这里。</p><button class="button primary" id="history-start">开始准备 →</button></div>');
  if($('#history-start'))$('#history-start').onclick=()=>navigate('setup');
  document.querySelectorAll('[data-view]').forEach(b=>b.onclick=()=>renderReport(state.records.find(s=>s.id===b.dataset.view)));
  document.querySelectorAll('[data-delete]').forEach(b=>b.onclick=()=>{if(confirm('删除这条本地练习记录？删除后无法恢复，建议先导出。')){state.records=state.records.filter(s=>s.id!==b.dataset.delete);save();renderHistory();}});
}
function renderSettings() {
  $('#main').innerHTML=heading('MAKE IT YOURS','连接模型，开始真实练习。','API 密钥由云端服务读取，网页不会保存或显示密钥。')+`<div class="settings-block"><section class="panel content-panel"><h2>模型连接</h2><p>${configured?'已配置模型：'+esc(model)+'。首次请求成功后才能确认接口可用。':'尚未配置模型。当前只能体验固定问题的流程演示。'}</p><p>在微信云托管的服务环境变量中，填入密钥和你有权限使用的模型：</p><code>OPENAI_API_KEY=你的密钥\nOPENAI_MODEL=你的模型名称\nAI_API_MODE=responses\nOPENAI_BASE_URL=https://api.openai.com/v1</code><p>修改后重新发布服务版本。兼容 Chat Completions 的服务可使用 AI_API_MODE=chat，并填写相应接口地址。默认使用 JSON Schema 结构化输出；DeepSeek 官方接口已适配 JSON 模式，并由服务端校验结果结构。</p><button class="button secondary" id="refresh-status">重新检查配置</button></section><section class="panel content-panel"><h2>你的资料和练习记录</h2><p>简历、JD、准备清单、对话与复盘保存在当前浏览器的本地存储中，不跨设备同步。AI 请求会把本次素材与对话发送到你配置的模型供应商；应用服务不会将内容写入数据库或日志。</p><p>请仅录入可用于面试练习的材料。你可以在记录页逐条导出复盘，或清空此应用的全部本地数据。</p><button class="button secondary" id="logout">退出登录</button> <button class="button secondary danger" id="clear-data">清空本应用的本地数据</button></section></div>`;
  $('#refresh-status').onclick=async()=>{await refreshStatus();renderSettings();toast(configured?'配置已读取。请通过一次正式面试验证连接。':'仍未检测到模型配置。');};
  $('#logout').onclick=async()=>{ const r=await fetch('/api/csrf');const c=await r.json();const form=document.createElement('form');form.method='POST';form.action='/logout';const token=document.createElement('input');token.type='hidden';token.name=c.parameterName;token.value=c.token;form.append(token);document.body.append(form);form.submit(); };
  $('#clear-data').onclick=()=>{if(confirm('这会删除此应用在当前浏览器保存的简历、JD 和所有练习记录，无法恢复。确定清空？')){localStorage.removeItem(KEY);state={config:{...defaults},current:null,records:[],prep:null};navigate('setup');toast('本应用的本地数据已清空。');}};
}
async function refreshStatus(){try{const r=await fetch('/api/status');if(!r.ok)throw Error();const data=await r.json();configured=data.configured;model=data.model||'';}catch{configured=false;toast('服务连接失败或登录过期，请刷新页面重新登录。');}shell();}
await refreshStatus();render();if(storageError)toast('旧记录无法读取，已打开空白工作台。');
