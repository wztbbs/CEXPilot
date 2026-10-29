/* 动态澄清表单：只呈现待补值；文本通过 DOM 写入，不解释模型提供的 HTML。 */
(function (root) {
  'use strict';
  function element(tag, text, parent) {
    const node = document.createElement(tag);
    if (text !== undefined) node.textContent = text;
    if (parent) parent.appendChild(node);
    return node;
  }
  function render(target, fields, config) {
    if (!Array.isArray(fields) || !fields.length) return null;
    const form = element('form', undefined, target);
    form.className = 'clarification-form';
    element('strong', '补充查询信息', form);
    const controls = [];
    fields.forEach(field => {
      const label = element('label', undefined, form);
      element('span', field.label, label);
      const input = element(field.type === 'select' ? 'select' : 'input', undefined, label);
      input.required = true;
      if (field.type === 'select') {
        const empty = element('option', '请选择', input);
        empty.value = '';
        (field.options || []).forEach(value => { element('option', value, input).value = value; });
      } else {
        input.type = ['text', 'number', 'date'].includes(field.type) ? field.type : 'text';
        input.placeholder = field.placeholder || '';
        if (input.type === 'number') {
          input.step = 'any';
          if (field.min !== null && field.min !== undefined) input.min = field.min;
          if (field.max !== null && field.max !== undefined) input.max = field.max;
        } else if (input.type === 'text') input.maxLength = 200;
      }
      if (field.hint) element('small', field.hint, label);
      input.addEventListener('input', () => input.setCustomValidity(''));
      controls.push({field, input});
    });
    const button = element('button', '继续查询', form);
    button.type = 'submit';
    const status = element('p', '', form);
    status.setAttribute('role', 'status');
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
      for (const item of controls) {
        item.input.setCustomValidity(item.input.value.trim() ? '' : '请填写' + item.field.label);
      }
      if (!form.reportValidity()) return;
      const question = '补充本轮查询条件：\n' + controls.map(item => item.field.label
        + (item.field.hint ? '（' + item.field.hint + '）' : '') + '：' + item.input.value.trim()).join('\n');
      submitting = true;
      status.textContent = '';
      refresh();
      try {
        submitted = (await config.onSubmit(question)) === true;
        if (!submitted) status.textContent = '尚未确认提交结果，请查看对话提示后继续。';
      } catch (error) {
        status.textContent = error.message || '提交失败，请稍后再试。';
      } finally {
        submitting = false;
        refresh();
      }
    });
    refresh();
    return {refresh};
  }
  root.ClarificationForm = {render};
})(window);
