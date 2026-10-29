/* 每项统一为自由文本输入；参考、提示和旧控件元数据都不限制用户表达。 */
(function (root) {
  'use strict';
  function element(tag, text, parent) {
    const node = document.createElement(tag);
    if (text !== undefined) node.textContent = text;
    if (parent) parent.appendChild(node);
    return node;
  }
  let idSequence = 0;
  function render(target, fields, config) {
    if (!Array.isArray(fields) || !fields.length) return null;
    const form = element('form', undefined, target);
    form.className = 'clarification-form';
    element('strong', fields.some(f => f.reason === 'invalid' || f.reason === 'confirm') ? '请核对查询信息' : '补充查询信息', form);
    const controls = [];
    fields.forEach(field => {
      const label = element('label', undefined, form);
      element('span', field.label, label);
      const input = element('input', undefined, label);
      input.type = 'text'; input.required = true; input.maxLength = 200;
      input.placeholder = field.placeholder || '请输入' + field.label;
      if (field.value !== null && field.value !== undefined) {
        const value = Array.isArray(field.value) ? field.value.join('、') : String(field.value);
        if (value) element('small', '上次填写：' + value, label).className = 'clarification-previous';
        input.value = value;
      }
      if (field.message) {
        const reason = ['invalid', 'confirm'].includes(field.reason) ? field.reason : 'missing';
        const message = element('p', (reason === 'invalid' ? '需要修改：' : reason === 'confirm' ? '请确认：' : '') + field.message, label);
        message.className = 'clarification-reason ' + reason;
        message.id = 'clarification-reason-' + (++idSequence);
        input.setAttribute('aria-describedby', message.id);
        if (reason === 'invalid') input.setAttribute('aria-invalid', 'true');
      }
      const refs = Array.isArray(field.options) ? field.options.filter(v => typeof v === 'string' && v.trim()) : [];
      if (refs.length) element('small', '参考：' + refs.join('、'), label).className = 'clarification-references';
      if (field.hint) element('small', field.hint, label);
      input.addEventListener('input', () => {
        input.setCustomValidity('');
        input.setAttribute('aria-invalid', 'false');
      });
      controls.push({field, input});
    });
    const button = element('button', '继续查询', form); button.type = 'submit';
    const status = element('p', '', form); status.setAttribute('role', 'status');
    let submitting = false, submitted = false;
    function refresh() {
      const active = config.isCurrent();
      const disabled = submitted || submitting || !active || !config.canSubmit();
      button.disabled = disabled;
      controls.forEach(item => { item.input.disabled = disabled; });
      button.textContent = submitted ? '已提交' : submitting ? '正在提交…' : '继续查询';
      if (!active && !submitted) status.textContent = '此轮已结束，请在最新一轮继续补充。';
    }
    form.addEventListener('submit', async event => {
      event.preventDefault();
      if (submitted || submitting || !config.isCurrent() || !config.canSubmit()) return;
      for (const item of controls) item.input.setCustomValidity(item.input.value.trim() ? '' : '请填写' + item.field.label);
      if (!form.reportValidity()) return;
      const question = '补充本轮查询条件：\n' + controls.map(item => item.field.label
        + (item.field.hint ? '（' + item.field.hint + '）' : '') + '：' + item.input.value.trim()).join('\n');
      const answers = {};
      controls.forEach(item => { answers[item.field.key] = item.input.value.trim(); });
      submitting = true; status.textContent = ''; refresh();
      try {
        submitted = (await config.onSubmit(question, answers)) === true;
        if (!submitted) status.textContent = '尚未确认提交结果，请查看对话提示后继续。';
      } catch (error) { status.textContent = error.message || '提交失败，请稍后再试。'; }
      finally { submitting = false; refresh(); }
    });
    refresh();
    return {refresh};
  }
  root.ClarificationForm = {render};
})(window);
