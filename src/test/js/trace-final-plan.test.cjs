const {test}=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const vm=require('node:vm');
const source=fs.readFileSync('src/main/resources/static/trace.html','utf8').match(/<script>([\s\S]*?)<\/script>/)[1];

async function render(finalPlan,compiled) {
  const elements={};
  const element=()=>({value:'',style:{},innerHTML:'',textContent:'',addEventListener(){},querySelectorAll(){return[];}});
  const data={trace:{question:'查询',model:'model-one',status:'SUCCESS',answer:'答案'},
    planner_attempts:[{attempt:1,model:'model-one',validation_status:'FAILED'},
      {attempt:2,model:'model-two',validation_status:'PASSED'}],
    final_plan:finalPlan,final_compiled_plan:compiled,
    events:[{event_type:'LLM_CALL',name:'dag_planner',output_json:JSON.stringify({content:'BAD_ONLY'})},
      {event_type:'PLAN',error:'OLD_ERROR',output_json:'{"plan":{"metrics":[{"id":"FAILED_PLAN"}]}}'},
      {event_type:'PLAN_COMPILED',output_json:'{"nodes":[{"id":"PROVISIONAL_NODE"}]}'}]};
  vm.runInNewContext(source,{document:{getElementById:id=>elements[id]||(elements[id]=element())},
    location:{search:'?id=test'},URLSearchParams,
    fetch:()=>Promise.resolve({ok:true,status:200,json:()=>Promise.resolve(data)})});
  await new Promise(resolve=>setImmediate(resolve));
  assert.equal(elements.errorBox.textContent,'');
  return elements.content.innerHTML;
}

test('页面只展示API确认的最终计划与对应模型，图使用最终编译计划',async()=>{
  const html=await render({metrics:[{id:'FINAL_ONLY'}],calculations:[]},
    {nodes:[{id:'FINAL_NODE',tool:'calculate',depends_on:[]}]});
  assert.match(html,/FINAL_ONLY/);assert.match(html,/model-two/);assert.match(html,/第 2 次尝试/);
  assert.match(html,/FINAL_NODE/);
  for(const text of ['BAD_ONLY','FAILED_PLAN','OLD_ERROR','PROVISIONAL_NODE']) assert.equal(html.includes(text),false);
});

test('没有最终可执行计划时不把早期编译成功的候选展示成成功',async()=>{
  const html=await render(null,null);
  assert.match(html,/没有校验成功的可执行 Plan/);
  assert.equal(html.includes('PROVISIONAL_NODE'),false);
  assert.equal(html.includes('FAILED_PLAN'),false);
});
