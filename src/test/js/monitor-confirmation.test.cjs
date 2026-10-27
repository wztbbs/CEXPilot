const {test}=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const vm=require('node:vm');
const source=fs.readFileSync('src/main/resources/static/monitor-confirmation.js','utf8');
class Element {
  constructor(tag){ this.tag=tag; this.children=[]; this.events={}; this.value=''; this.textContent=''; }
  appendChild(child){ this.children.push(child); }
  replaceChildren(){ this.children=[]; }
  setAttribute(){}
  addEventListener(name,cb){ this.events[name]=cb; }
}
function all(node){ return [node,...node.children.flatMap(all)]; }
const tick=()=>new Promise(resolve=>setImmediate(resolve));
function setup(status='PENDING_CONFIRMATION'){
  const parent=new Element('main'), calls=[];
  let current={ruleId:'rule-1',version:1,specHash:'hash-1',status,requiredHits:2,timezone:'Asia/Shanghai',
    rules:[{description:'<img src=x onerror=attack()> BTC跌2%'}],profile:{emailBound:true,email:'friend@example.com'},
    candidate:{plan:{example:'safe'}},executionAvailable:true,canConfirm:true,recipient:'friend@example.com'};
  let failConfirm=false;
  const sandbox={window:{confirm:()=>true},document:{createElement:tag=>new Element(tag)},URLSearchParams,
    fetch:async(url,options)=>{
      calls.push({url,options});
      if(url.endsWith('/executions')) return {ok:true,json:async()=>[{executionId:'exec-1',status:'DONE',attemptCount:2,checkpointNodeCount:3}]};
      if(url.endsWith('/events')) return {ok:true,json:async()=>[{triggeredAt:'2026-10-27T06:00:00Z',deliveryStatus:'SENT',simulated:true,evidence:{}}]};
      const body=options.body && JSON.parse(options.body);
      if(url.endsWith('/profile')) current.profile={emailBound:true,email:body.email};
      if(url.endsWith('/confirm')){
        if(failConfirm){ current={...current,version:2,status:'SUPERSEDED'}; return {ok:false,json:async()=>({error:'草稿已更新'})}; }
        current={...current,status:'ACTIVE'};
      }
      if(url.endsWith('/reject') || url.endsWith('/stop')) current={...current,status:'DELETED'};
      return {ok:true,json:async()=>current};
    }};
  vm.runInNewContext(source,sandbox);
  sandbox.window.MonitorConfirmation.render(parent,{confirmation:current});
  return {parent,calls,setFail:()=>{failConfirm=true;},setCard:data=>{current={...current,...data};},
    button:text=>all(parent).find(n=>n.tag==='button'&&n.textContent===text),
    click:async function(text){ const b=this.button(text); assert.ok(b,text); b.events.click(); await tick(); await tick(); }};
}
test('渲染和读取历史不启用任务；只有明确确认携带可见版本、hash和邮箱',async()=>{
  const f=setup(); await tick();
  assert.ok(f.calls.every(c=>!c.options.method));
  assert.ok(all(f.parent).some(n=>n.tag==='li'&&n.textContent.includes('<img')));
  assert.ok(!all(f.parent).some(n=>n.tag==='img'));
  await f.click('确认并启用监控');
  const post=f.calls.find(c=>c.url.endsWith('/confirm'));
  assert.deepEqual(JSON.parse(post.options.body),{version:1,specHash:'hash-1',recipient:'friend@example.com'});
  assert.equal(post.options.headers['X-Monitor-Action'],'1');
  assert.equal(post.options.credentials,'same-origin');
  assert.ok(f.button('停止监控')); assert.equal(f.button('确认并启用监控'),undefined);
});
test('邮箱保存不确认；取消草稿后不能再确认',async()=>{
  const f=setup(); await tick();
  const input=all(f.parent).find(n=>n.tag==='input'); input.value='new@example.com'; input.events.input();
  assert.equal(f.button('确认并启用监控').disabled,true);
  await f.click('保存邮箱');
  assert.ok(f.calls.find(c=>c.url.endsWith('/profile')));
  assert.ok(!f.calls.some(c=>c.url.endsWith('/confirm')));
  await f.click('取消此草稿');
  assert.equal(f.button('确认并启用监控'),undefined);
});
test('旧版本失败后仅刷新状态，不能静默确认新版',async()=>{
  const f=setup(); await tick(); f.setFail();
  await f.click('确认并启用监控');
  assert.equal(f.calls.filter(c=>c.url.endsWith('/confirm')).length,1);
  assert.ok(f.button('查看最新版本并重新核对'));
  assert.equal(f.button('确认并启用监控'),undefined);
});
test('刷新恢复真实 ACTIVE 状态；缺邮箱或服务不可用时禁用确认',async()=>{
  const f=setup(); f.setCard({status:'ACTIVE'}); await tick();
  await f.click('刷新状态'); assert.ok(f.button('停止监控'));
  const g=setup(); g.setCard({canConfirm:false,executionAvailable:false,unavailableReason:'Resend未配置'}); await tick();
  await g.click('刷新状态'); assert.equal(g.button('确认并启用监控').disabled,true);
});

test('已启用卡片可以读取执行状态和恢复进度，不触发新检查',async()=>{
  const f=setup('ACTIVE'); await tick(); await f.click('查看执行记录');
  assert.ok(f.calls.some(c=>c.url.endsWith('/executions')));
  assert.ok(f.calls.every(c=>!c.options.method));
  assert.ok(all(f.parent).some(n=>n.tag==='pre'&&n.textContent.includes('exec-1')&&n.textContent.includes('DONE')));
});

test('模拟邮件模式在确认前明确提示不会真实发送',async()=>{
  const f=setup(); f.setCard({mailMode:'DRY_RUN'}); await tick(); await f.click('刷新状态');
  assert.ok(all(f.parent).some(n=>n.textContent.includes('邮件模拟模式')&&n.textContent.includes('不会发送真实邮件')));
  assert.ok(f.button('确认并启用监控'));
});

test('历史模拟投递即使已标为SENT也显示未实际发送',async()=>{
  const f=setup('ACTIVE'); await tick(); await f.click('查看告警记录');
  assert.ok(all(f.parent).some(n=>n.textContent.includes('SENT')&&n.textContent.includes('模拟，未实际发送')));
  assert.ok(f.calls.every(c=>!c.options.method));
});
