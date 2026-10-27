/* Monitor 确认卡片：服务端给出规则，明确按钮确认；所有用户文本均经 textContent 写入。 */
(function (root) {
  'use strict';
  const rendered = new Set();
  const labels = {PENDING_CONFIRMATION:'待确认 · 尚未启用',ACTIVE:'监控已启用',SUPERSEDED:'此草稿已被新版本替代',DELETED:'监控已停止'};
  async function api(path, body, method) {
    const options = {credentials:'same-origin',cache:'no-store'};
    if (method) Object.assign(options,{method:method,headers:{'Content-Type':'application/json','X-Monitor-Action':'1'},body:JSON.stringify(body)});
    const response = await fetch('/api/monitor-flow' + path, options);
    const data = await response.json();
    if (!response.ok) throw new Error(data.error || '操作失败，请刷新状态后重试');
    return data;
  }
  function element(tag, text, parent) {
    const node=document.createElement(tag);
    if (text !== undefined) node.textContent=text;
    if (parent) parent.appendChild(node);
    return node;
  }
  function render(target, planning) {
    const initial=planning && planning.confirmation;
    if (!initial || !initial.ruleId) return;
    rendered.add(initial.ruleId+'@'+initial.version);
    const panel=element('section',undefined,target); panel.className='monitor-confirmation';
    let card=initial, busy=false;
    function message(text) { const n=element('p',text,panel); n.setAttribute('role','status'); }
    function request() { return {version:card.version,specHash:card.specHash}; }
    async function refresh(latest) {
      const data=await api('/rules/'+encodeURIComponent(card.ruleId)+(latest?'':'?version='+card.version));
      card=data; rendered.add(card.ruleId+'@'+card.version); draw();
    }
    async function act(button, action) {
      if(busy) return;
      busy=true; button.disabled=true;
      try { await action(); }
      catch(error) {
        // 即使确认响应丢失，重新读取同一版本也能恢复实际状态；不会自动确认新版本。
        try { await refresh(false); } catch(ignored) { draw(); }
        message(error.message);
      } finally { busy=false; button.disabled=false; }
    }
    function button(text,action) {
      const b=element('button',text,panel); b.type='button';
      b.addEventListener('click',()=>act(b,action)); return b;
    }
    function draw() {
      panel.replaceChildren();
      element('strong',(labels[card.status] || card.status)+' · 版本 '+card.version,panel);
      const rules=element('ol',undefined,panel);
      (card.rules || []).forEach(rule=>element('li',rule.description,rules));
      element('p','以上条件全部同时满足（AND）。每5分钟检查；连续 '+card.requiredHits+' 次满足后发邮件，恢复不满足后才能再次提醒。',panel);
      element('p','时间基准：'+card.timezone+'；时间指标采用完整、已收盘窗口，自动采样固定为5m。',panel);
      if(card.mailMode==='DRY_RUN') message('邮件模拟模式：只记录成功日志，不会发送真实邮件。');
      if(!card.executionAvailable && card.unavailableReason) message(card.unavailableReason);
      if(card.status === 'PENDING_CONFIRMATION') {
        const label=element('label','通知邮箱（灰度试用，无需验证码）',panel);
        const email=element('input',undefined,label); email.type='email'; email.maxLength=254;
        email.value=(card.profile || {}).email || ''; email.autocomplete='email';
        button('保存邮箱',async()=>{
          await api('/profile',{email:email.value.trim()},'PUT'); await refresh(false);
        });
        element('p','更换邮箱后请先保存；本次确认会固定使用下方已保存的地址。',panel);
        const recipient=(card.profile || {}).email;
        if(recipient) element('p','本次收件邮箱：'+recipient,panel);
        const confirm=button('确认并启用监控',async()=>{
          card=await api('/rules/'+encodeURIComponent(card.ruleId)+'/confirm',Object.assign(request(),{recipient:recipient}),'POST'); draw();
        });
        confirm.disabled=!card.canConfirm;
        email.addEventListener('input',()=>{ confirm.disabled=!card.canConfirm || email.value.trim()!==recipient; });
        button('取消此草稿',async()=>{
          await api('/rules/'+encodeURIComponent(card.ruleId)+'/reject',request(),'POST'); await refresh(false);
        });
      } else if(card.status === 'ACTIVE') {
        element('p','告警收件邮箱：'+card.recipient,panel);
        button('停止监控',async()=>{
          if(!root.confirm('停止这条监控？停止后不再检查和发送新告警。')) return;
          await api('/rules/'+encodeURIComponent(card.ruleId)+'/stop',request(),'POST'); await refresh(false);
        });
      } else if(card.status === 'SUPERSEDED') {
        button('查看最新版本并重新核对',()=>refresh(true));
      }
      if(card.status === 'ACTIVE' || card.status === 'DELETED') {
        button('查看执行记录',async()=>{
          const executions=await api('/rules/'+encodeURIComponent(card.ruleId)+'/executions');
          const history=element('details',undefined,panel); history.open=true;
          element('summary','最近执行、尝试次数与恢复进度',history);
          if(!executions.length) element('p','暂无已排期的执行',history);
          executions.forEach(execution=>element('pre',JSON.stringify(execution,null,2),history));
        });
        button('查看告警记录',async()=>{
          const events=await api('/rules/'+encodeURIComponent(card.ruleId)+'/events');
          const history=element('details',undefined,panel); history.open=true;
          element('summary','最近告警与投递状态',history);
          if(!events.length) element('p','暂无触发告警',history);
          events.forEach(event=>{
            element('p','触发时间：'+event.triggeredAt+'；邮件状态：'+event.deliveryStatus+(event.simulated?'（模拟，未实际发送）':''),history);
            element('pre',JSON.stringify(event.evidence,null,2),history);
          });
        });
      }
      const detail=element('details',undefined,panel);
      element('summary','查看完整规则定义',detail);
      element('pre',JSON.stringify(card.candidate,null,2),detail);
      button('刷新状态',()=>refresh(false));
    }
    draw();
    // 历史里的卡片只存快照；展示后立即以数据库中的版本状态为准。
    refresh(false).catch(error=>{ panel.replaceChildren(); message('无法核实规则状态：'+error.message); button('重试读取',()=>refresh(false)); });
    return panel;
  }
  async function openLinked(target,search) {
    const id=new URLSearchParams(search || '').get('monitor');
    if(!id) return false;
    try { render(target,{confirmation:await api('/rules/'+encodeURIComponent(id))}); }
    catch(error) { element('p','无法打开监控：'+error.message,target); }
    return true;
  }
  async function recover(createTarget) {
    const cards=await api('/rules');
    const missing=cards.filter(card=>(card.status==='PENDING_CONFIRMATION' || card.status==='ACTIVE') && !rendered.has(card.ruleId+'@'+card.version));
    if(missing.length) {
      const target=createTarget(); missing.forEach(card=>render(target,{confirmation:card}));
    }
  }
  root.MonitorConfirmation={render:render,openLinked:openLinked,recover:recover};
})(window);
