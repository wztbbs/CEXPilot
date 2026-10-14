// 对实际页面脚本做本地协议验证；DOM/fetch 为桩，不访问服务。
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const source = fs.readFileSync('src/main/resources/static/index.html', 'utf8').match(/<script>([\s\S]*?)<\/script>/)[1];

class Element {
  constructor() { this.value = ''; this.style = {}; this.children = []; this.events = {}; this.textContent = ''; }
  appendChild(child) { child.parent = this; this.children.push(child); }
  addEventListener(name, callback) { this.events[name] = callback; }
  querySelector(selector) { return this.children.find(child => '.' + child.className === selector); }
  remove() { if (this.parent) this.parent.children = this.parent.children.filter(c => c !== this); }
  focus() {}
  contains(child) { return this.children.includes(child); }
}
const tick = () => new Promise(resolve => setImmediate(resolve));
async function tab(parent, streams) {
  const elements = Object.fromEntries(['app','messages','question','send','progress','input-box'].map(id => [id,new Element()]));
  const requests = [];
  const document = { getElementById: id => elements[id] || (elements[id] = new Element()), createElement: () => new Element(), body: {scrollHeight:0} };
  const sandbox = {
    document, window: { scrollTo() {}, location: { reload() {} } }, TextDecoder, Intl,
    fetch: (url, options) => {
      if (!options) return Promise.resolve({ok:true,json: () => Promise.resolve({conversationId:'c1', parentTurnId:parent,
        messages:[{question:'之前的问题',answer:'之前的答案'}]})});
      requests.push(JSON.parse(options.body));
      const events = streams.shift();
      let consumed = false;
      return Promise.resolve({ok:true,body:{getReader: () => ({read: () => {
        if (consumed) return Promise.resolve({done:true});
        consumed = true;
        return Promise.resolve({done:false,value:Buffer.from(events.map(([name,payload]) => 'event: '+name+'\ndata: '+JSON.stringify(payload)+'\n\n').join(''))});
      }})}});
    }
  };
  vm.runInNewContext(source, sandbox);
  await tick();
  return { requests, elements, send: async (text) => {
    elements.question.value = text;
    elements.send.events.click();
    await tick();
  }};
}

test('各标签页只提交自己看到的父轮次，完成后推进而忙碌不推进', async () => {
  const a = await tab('btc-turn', [
    [['meta',{conversationId:'c1',turnId:'new-btc'}],['done',{turnId:'new-btc',busy:false}]],
    [['meta',{conversationId:'c1'}],['done',{busy:true}]],
    [['done',{turnId:'later',busy:false}]]
  ]);
  const b = await tab('eth-turn', [[['done',{turnId:'new-eth',busy:false}]]]);
  await b.send('它现在呢'); await a.send('它现在呢'); await a.send('下一问'); await a.send('再问');
  assert.equal(b.requests[0].parentTurnId,'eth-turn');
  assert.equal(a.requests[0].parentTurnId,'btc-turn');
  assert.equal(a.requests[1].parentTurnId,'new-btc');
  assert.equal(a.requests[2].parentTurnId,'new-btc');
});

test('冲突后不能把服务端新父轮次直接采用并静默重发', async () => {
  const a = await tab('btc-turn', [[['error',{code:'CONTEXT_CONFLICT',currentParentTurnId:'eth-turn',error:'已更新'}]]]);
  await a.send('它现在呢'); await a.send('重试');
  assert.equal(a.requests.length,1);
  assert.equal(a.requests[0].parentTurnId,'btc-turn');
  assert.equal(a.elements.send.disabled,true);
});

test('失败轮次提交确认后可以重试，只有meta然后断流则必须刷新', async () => {
  const failed = await tab('old', [[['meta',{conversationId:'c1',turnId:'failed'}],['error',{turnId:'failed',error:'执行失败'}]],
    [['done',{turnId:'retried',busy:false}]]]);
  await failed.send('查询'); await failed.send('再试一次');
  assert.equal(failed.requests[1].parentTurnId,'failed');
  const dropped = await tab('old', [[['meta',{conversationId:'c1',turnId:'unconfirmed'}]]]);
  await dropped.send('查询'); await dropped.send('重试');
  assert.equal(dropped.requests.length,1);
  assert.equal(dropped.elements.send.disabled,true);
});
