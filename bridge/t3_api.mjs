// T3 protocol 2 client. Credentials and transport stay on the environment host.
import fs from 'node:fs';
import path from 'node:path';
import os from 'node:os';

const active = new Set(['preparing', 'starting', 'running', 'waiting']);
export function visibleMessages(rows) {
  return rows.flatMap(row => {
    const item = row.item;
    if (!['user_message','assistant_message','proposed_plan'].includes(item.type)) return [];
    return [{id: item.id, position: row.position, role: item.type === 'user_message' ? 'user' : 'assistant',
      text: item.text ?? item.markdown ?? '', streaming: !!item.streaming,
      updatedAt: item.updatedAt, inherited: row.visibility === 'inherited'}];
  });
}

export function normalize(snapshot) {
  const p = snapshot.projection;
  const runs = [...p.runs].sort((a,b) => b.ordinal-a.ordinal);
  const run = runs.find(r => active.has(r.status)) ?? runs[0];
  const items = [...p.turnItems, ...p.visibleTurnItems.map(row => row.item)];
  const requests = p.runtimeRequests.filter(r => r.status === 'pending').map(r => {
    const item = items.find(i => i.requestId === r.id);
    return {id:r.id, kind:r.kind, resumable:r.responseCapability?.type !== 'not_resumable',
      questions:item?.questions ?? [], prompt:item?.prompt ?? item?.title ?? 'Review this request in T3 Code.',
      options:item?.options ?? [], requestKind:item?.requestKind ?? r.kind};
  });
  return {threadId:p.thread.id, title:p.thread.title, projectId:p.thread.projectId,
    status:requests.length ? 'waiting' : run?.status ?? 'idle', active:!!runs.find(r => active.has(r.status)),
    model:p.thread.modelSelection?.model ?? '', requests, messages:visibleMessages(p.visibleTurnItems),
    historyCursor:snapshot.historyCursor ?? null, hasMoreHistory:!!snapshot.hasMoreHistory,
    sequence:snapshot.snapshotSequence};
}

export function prepare(input, snapshot) {
  const p = snapshot.projection;
  if (p.thread.id !== input.threadId || p.thread.deletedAt || p.thread.archivedAt) throw new Error('This task is no longer available.');
  const base = {commandId:'tv-'+input.clientRequestId, threadId:input.threadId};
  if (input.action === 'send') {
    if (typeof input.text !== 'string' || !input.text.trim() || input.text.length > 16000) throw new Error('Enter a reply of 1–16,000 characters.');
    if (!['auto','queue','steer'].includes(input.mode)) throw new Error('Invalid delivery mode.');
    const running = [...p.runs].sort((a,b)=>b.ordinal-a.ordinal).find(r=>active.has(r.status));
    if (input.mode === 'steer' && !running) throw new Error('The task has finished. Choose Send or Queue follow-up.');
    const command = {...base,type:'message.dispatch',messageId:'tv-message-'+input.clientRequestId,
      createdBy:'user',creationSource:'mobile',text:input.text.trim(),attachments:[],
      dispatchMode:input.mode === 'queue' ? {type:'queue_after_active'} : {type:'start_immediately'}};
    // Server serializes auto/steer against current state to avoid racing a completion.
    if (input.mode !== 'queue') command.deliveryIntent = input.mode;
    return {command, kind:'message', messageId:command.messageId, requestedMode:input.mode};
  }
  const request = p.runtimeRequests.find(r=>r.id===input.requestId && r.status==='pending');
  if (!request || request.responseCapability?.type === 'not_resumable') throw new Error('This request has already been answered or can no longer be resumed. Refresh the task.');
  const item = [...p.turnItems,...p.visibleTurnItems.map(r=>r.item)].find(i=>i.requestId===request.id);
  if (input.action === 'answer') {
    if (request.kind !== 'user_input' || !item?.questions?.length) throw new Error('This is not a text question.');
    const answers = {};
    for (const q of item.questions) {
      const value = input.answers?.[q.id];
      if (!Array.isArray(value) || value.some(v=>typeof v!=='string' || v.length>16000)) throw new Error('Invalid question answer.');
      if (q.required !== false && !value.some(v=>v.trim())) throw new Error('Answer every required question.');
      if (!q.multiSelect && value.length>1) throw new Error('Choose one answer for this question.');
      if (q.allowCustomAnswer === false && value.some(v=>!(q.options??[]).some(o=>(o.value??o.label)===v))) throw new Error('Choose one of the offered answers.');
      answers[q.id] = value;
    }
    return {command:{...base,type:'runtime-request.respond',requestId:request.id,answers},kind:'answer'};
  }
  if (input.action === 'approve') {
    if (item?.type !== 'approval_request') throw new Error('This request is not a permission approval.');
    const allowed = item.options?.length ? item.options.map(o=>o.decision) : ['accept','decline','cancel'];
    // Persistent/session-wide grants belong in the full T3 client.
    if (!['accept','decline','cancel'].includes(input.decision) || !allowed.includes(input.decision)) throw new Error('This permission choice must be reviewed in T3 Code.');
    return {command:{...base,type:'runtime-request.respond',requestId:request.id,decision:input.decision},kind:'approval'};
  }
  throw new Error('Unknown action.');
}

export class T3Client {
  constructor(userdata, sessionPath) {
    this.userdata = userdata;
    this.sessionPath = sessionPath;
  }
  async http(route, method='GET', timeout=25000) {
    const {origin} = JSON.parse(fs.readFileSync(path.join(this.userdata,'server-runtime.json'),'utf8'));
    const url = new URL(origin);
    if (!['127.0.0.1','localhost','[::1]'].includes(url.hostname)) throw new Error('T3 transport must use host-local loopback.');
    const session = JSON.parse(fs.readFileSync(this.sessionPath,'utf8'));
    const response = await fetch(new URL(route,origin), {method,
      headers:{Authorization:'Bearer '+session.token,'x-t3-orchestration-protocol':'2'},signal:AbortSignal.timeout(timeout)});
    if (response.status === 401 || response.status === 403) throw new Error('T3 connection needs renewed authorization.');
    if (response.status === 404) throw new Error('Task not found in this environment.');
    if (!response.ok) throw new Error('T3 returned HTTP '+response.status+'. Try again.');
    return response.json();
  }
  async snapshot(threadId, timeout=25000) {return this.http('/api/orchestration/threads/'+encodeURIComponent(threadId)+'/bounded','GET',timeout);}
  async dispatch(command) {
    const {origin} = JSON.parse(fs.readFileSync(path.join(this.userdata,'server-runtime.json'),'utf8'));
    const {ticket} = await this.http('/api/auth/websocket-ticket','POST');
    const url = new URL('/ws',origin);url.protocol='ws:';url.searchParams.set('orchestrationProtocol','2');url.searchParams.set('wsTicket',ticket);
    return new Promise((resolve,reject)=>{
      const ws = new WebSocket(url);let done=false;
      const finish = (error,value)=>{if(done)return;done=true;clearTimeout(timer);ws.close();error?reject(error):resolve(value);};
      const timer=setTimeout(()=>finish(new Error('Delivery not confirmed. Retry this same draft to check safely.')),25000);
      ws.onopen=()=>ws.send(JSON.stringify({_tag:'Request',id:'1',tag:'orchestration.dispatchCommand',payload:command,headers:[]}));
      ws.onerror=()=>finish(new Error('T3 connection interrupted. Retry this same draft to check safely.'));
      ws.onclose=()=>{if(!done)finish(new Error('Delivery not confirmed. Retry this same draft to check safely.'));};
      ws.onmessage=event=>{
        try {
          const frame=JSON.parse(event.data);
          if(frame._tag==='Ping'){ws.send(JSON.stringify({_tag:'Pong'}));return;}
          if(frame._tag!=='Exit'||String(frame.requestId)!=='1')return;
          if(frame.exit?._tag==='Success') finish(null,frame.exit.value);
          else {
            const error=frame.exit?.cause?.find(c=>c._tag==='Fail')?.error;
            finish(new Error(typeof error?.message==='string'?error.message:'T3 could not accept this action. Refresh the task and try again.'));
          }
        }catch {finish(new Error('Unexpected T3 response.'));}
      };
    });
  }
}

export async function operate(input, client) {
  if(input.operation==='dispatch') {
    if(!['message.dispatch','runtime-request.respond'].includes(input.prepared?.command?.type)) throw new Error('Unsupported command.');
    const receipt=await client.dispatch(input.prepared.command);
    let delivery=input.prepared.kind==='message'?'accepted':'answered';
    if(input.prepared.kind==='message') {
      try {
        const p=(await client.snapshot(input.prepared.command.threadId,5000)).projection;
        const item=p.turnItems.find(i=>i.messageId===input.prepared.messageId);
        const run=p.runs.find(r=>r.userMessageId===input.prepared.messageId);
        if(item?.inputIntent==='steer')delivery='steered';
        else if(run)delivery=run.status==='queued'?'queued':'started';
      } catch { /* The durable receipt still confirms acceptance if refresh is unavailable. */ }
    }
    return {accepted:true,sequence:receipt.sequence,kind:input.prepared.kind,messageId:input.prepared.messageId??null,
      delivery};
  }
  if(typeof input.threadId!=='string'||!input.threadId||input.threadId.length>200)throw new Error('Invalid task.');
  if(input.operation==='thread')return normalize(await client.snapshot(input.threadId));
  if(input.operation==='history') {
    if(typeof input.cursor!=='string'||input.cursor.length>16000)throw new Error('Invalid history cursor.');
    const page=await client.http('/api/orchestration/threads/'+encodeURIComponent(input.threadId)+'/history?view=conversation&cursor='+encodeURIComponent(input.cursor));
    return {messages:visibleMessages(page.items),historyCursor:page.nextCursor,hasMoreHistory:page.hasMoreHistory};
  }
  if(input.operation==='prepare')return prepare(input,await client.snapshot(input.threadId));
  throw new Error('Unsupported operation.');
}

if(process.argv[1] && path.resolve(process.argv[1]) === path.resolve(new URL(import.meta.url).pathname)) {
  try {
    let data='';for await(const chunk of process.stdin){data+=chunk;if(data.length>100000)throw new Error('Request too large.');}
    const input=JSON.parse(data);
    const expand=p=>p.startsWith('~/')?path.join(os.homedir(),p.slice(2)):p;
    const result=await operate(input,new T3Client(expand(process.argv[2]),expand(process.argv[3])));
    process.stdout.write(JSON.stringify({ok:true,result}));
  }catch(error){process.stdout.write(JSON.stringify({ok:false,error:error.message?.includes('fetch failed')?'T3 is offline. Try again shortly.':error.message??'T3 connection failed.'}));process.exitCode=1;}
}
