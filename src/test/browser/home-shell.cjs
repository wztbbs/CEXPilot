// 本地浏览器协议测试：真实静态页面 + 假 API，不访问模型、交易所、数据库或邮件。
// NODE_PATH=<包含 playwright 的 node_modules> node src/test/browser/home-shell.cjs
const {chromium}=require('playwright');
const http=require('node:http');
const fs=require('node:fs');
const path=require('node:path');
const assert=require('node:assert/strict');
(async()=>{
  let asks=[],created=0,delayMonitors=false,releaseMonitors;
  const configured=[...fs.readFileSync('src/main/resources/home-suggestions.yml','utf8').matchAll(/  - id: (.*)\n    category: (.*)\n    query: (.*)/g)]
    .map(([,id,category,query])=>({id,category,query}));
  assert.equal(configured.length,16);
  const suggestions=['capabilities','spot-btc-price','spot-close-avg','btc-monitor']
    .map(id=>{const item=configured.find(q=>q.id===id);assert.ok(item);return item;});
  const card={ruleId:'r1',version:1,specHash:'hash',status:'ACTIVE',requiredHits:1,checkIntervalSeconds:300,timezone:'Asia/Shanghai',sourceConversationId:'c-old',createdAt:'2026-10-29T10:00:00Z',nextCheckAt:'2026-10-30T02:35:00Z',recipient:'test@example.com',rules:[{description:'币安 BTC/USDT 永续最近一小时跌幅超过 2%'}],latestEvaluation:{result:'NO_MATCH',scheduledAt:'2026-10-30T02:30:00Z'}};
  const server=http.createServer((req,res)=>{
    const u=new URL(req.url,'http://localhost');
    const json=data=>{res.setHeader('Content-Type','application/json');res.end(JSON.stringify(data));};
    if(u.pathname==='/api/home/suggestions')return json(suggestions);
    if(u.pathname==='/api/conversations'&&req.method==='POST')return json({conversationId:'c-new-'+(++created),messages:[]});
    if(u.pathname==='/api/conversations')return json([{conversationId:'c-old',title:'BTC 历史会话',lastActiveAt:'2026-10-29T10:00:00'}]);
    if(u.pathname==='/api/conversation/latest')return json({conversationId:null,parentTurnId:'',messages:[]});
    if(u.pathname==='/api/conversation/c-old')return json({conversationId:'c-old',parentTurnId:'old-turn',messages:[{question:'历史问题',answer:'历史答案',turnId:'old-turn'}]});
    if(u.pathname.startsWith('/api/conversation/c-new-'))return json({conversationId:u.pathname.split('/').pop(),parentTurnId:'',messages:[]});
    if(u.pathname==='/api/monitor-flow/rules'){if(delayMonitors){releaseMonitors=()=>json([card]);return;}return json([card]);}
    if(u.pathname==='/api/monitor-flow/rules/r1')return json(card);
    if(u.pathname==='/api/monitor-flow/rules/r1/executions')return json([
      {executionId:'a',ruleVersion:1,attemptCount:1,status:'DONE',result:'NO_MATCH',scheduledAt:'2026-10-30T02:30:00Z',evidence:{values:[{id:'条件1',value:-.82,threshold:-2,matched:false,unit:'percent'}]},notifications:[]},
      {executionId:'b',ruleVersion:1,attemptCount:1,status:'DONE',result:'MATCH',scheduledAt:'2026-10-30T02:25:00Z',evidence:{values:[{id:'条件1',value:-2.36,threshold:-2,matched:true,unit:'percent'}]},notifications:[{status:'SENT',simulated:true}]},
      {executionId:'c',ruleVersion:1,attemptCount:3,status:'FAILED',scheduledAt:'2026-10-30T02:20:00Z',lastError:'行情数据获取失败',notifications:[]}
    ]);
    if(u.pathname==='/api/ask/stream') {let raw='';req.on('data',b=>raw+=b);req.on('end',()=>{const data=JSON.parse(raw);asks.push(data);res.setHeader('Content-Type','text/event-stream');res.end('event: meta\ndata: '+JSON.stringify({conversationId:data.conversationId||'c-first'})+'\n\nevent: delta\ndata: {"text":"测试回答"}\n\nevent: done\ndata: {"turnId":"new-turn","busy":false,"clarify":[]}\n\n');});return;}
    const name=u.pathname==='/'?'index.html':u.pathname.slice(1);
    if(!['index.html','home-shell.js','home-shell.css','monitor-confirmation.js','clarification-form.js'].includes(name)){res.statusCode=404;return res.end();}
    res.setHeader('Content-Type',name.endsWith('.js')?'text/javascript':name.endsWith('.css')?'text/css':'text/html');res.end(fs.readFileSync(path.join('src/main/resources/static',name)));
  });
  await new Promise(r=>server.listen(0,'127.0.0.1',r));
  const browser=await chromium.launch({headless:true,channel:'chrome'});const page=await browser.newPage({viewport:{width:1440,height:1000}});const errors=[];page.on('pageerror',e=>errors.push(e.message));
  try {
    await page.goto('http://127.0.0.1:'+server.address().port);
    await page.waitForSelector('.suggestion');await page.waitForSelector('.history-item');
    assert.match(await page.locator('.suggestion').first().innerText(),/你能实现哪些功能/);
    await page.locator('.suggestion').first().click();assert.equal(await page.locator('#question').inputValue(),suggestions[0].query);assert.equal(asks.length,0);
    await page.screenshot({path:'/tmp/cexpilot-home-implemented.png'});
    await page.locator('.history-item').click();await page.waitForSelector('#messages .msg.assistant');assert.match(await page.locator('#messages').innerText(),/历史答案/);
    await page.locator('#question').fill('还没发送的草稿');
    await page.locator('#my-monitors').click();await page.waitForSelector('.monitor-card');await page.locator('.monitor-card').click();await page.waitForSelector('.record');
    assert.equal(await page.locator('.record').count(),3);assert.match(await page.locator('.record').nth(2).innerText(),/未完成/);
    await page.locator('.record > summary').nth(1).click();assert.match(await page.locator('.record').nth(1).innerText(),/模拟发送/);assert.match(await page.locator('.record').nth(1).innerText(),/2.36/);
    await page.screenshot({path:'/tmp/cexpilot-monitor-implemented.png'});
    await page.getByRole('button',{name:'查看创建时的对话'}).click();await page.waitForSelector('#app:not([hidden])');assert.equal(await page.locator('#question').inputValue(),'还没发送的草稿');
    await page.locator('#send').click();await page.waitForFunction(()=>document.querySelector('#send').disabled===false || document.querySelector('#question').value==='');await page.waitForTimeout(50);assert.equal(asks[0].parentTurnId,'old-turn');
    await page.locator('#new-chat').click();await page.waitForFunction(()=>document.getElementById('view-title').textContent==='新会话');assert.equal(await page.locator('#messages .msg').count(),0);
    await page.locator('.suggestion').first().click();await page.locator('#send').click();await page.waitForTimeout(80);assert.equal(asks[1].conversationId,'c-new-1');assert.equal(asks[1].parentTurnId,'');
    await page.setViewportSize({width:390,height:844});await page.locator('#toggle-nav').click();assert.equal(await page.locator('#toggle-nav').getAttribute('aria-expanded'),'true');await page.locator('#my-monitors').click();await page.waitForSelector('.monitor-card');assert.equal(await page.locator('#toggle-nav').getAttribute('aria-expanded'),'false');await page.waitForFunction(()=>document.getElementById('sidebar').getBoundingClientRect().right<=0);assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth>innerWidth),false);
    await page.screenshot({path:'/tmp/cexpilot-mobile-implemented.png'});assert.deepEqual(errors,[]);
    // 迟到的监控响应不能覆盖用户已打开的历史会话。
    delayMonitors=true;
    await page.locator('#toggle-nav').click();
    await page.locator('#my-monitors').click();
    await page.waitForSelector('#monitor-view:not([hidden])');
    await page.locator('#toggle-nav').click();await page.locator('.history-item').click();
    await page.waitForSelector('#app:not([hidden])');
    assert.ok(releaseMonitors);releaseMonitors();
    await page.waitForFunction(()=>document.getElementById('monitor-view').hidden);
    assert.match(await page.locator('#messages').innerText(),/历史答案/);
    console.log('PASS: recommendations, history, drafts, monitor evidence/states, new context, mobile navigation; no page errors.');
  } finally {await browser.close();await new Promise(r=>server.close(r));}
})().catch(e=>{console.error(e);process.exitCode=1;});
