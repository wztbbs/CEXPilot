/* 动态澄清表单：只呈现待补值；文本通过 DOM 写入，不解释模型提供的 HTML。 */
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
      const multi = field.type === 'multiselect';
      const label = element(multi ? 'fieldset' : 'label', undefined, form);
      if (multi) label.className = 'clarification-options';
      element(multi ? 'legend' : 'span', field.label, label);
      const inputs = [];
      let counter = null, selectionError = null;
      const lower = field.minSelections == null ? 1 : field.minSelections;
      const upper = field.maxSelections == null ? (field.options || []).length : field.maxSelections;
      if (multi) {
        (field.options || []).forEach(value => {
          const option = element('label', undefined, label); option.className = 'clarification-option';
          const checkbox = element('input', undefined, option); checkbox.type = 'checkbox'; checkbox.value = value;
          element('span', value, option);
          inputs.push(checkbox);
        });
        counter = element('small', '', label);
        selectionError = element('p', '', label);
        selectionError.className = 'clarification-selection-error';
        selectionError.setAttribute('role', 'alert');
      } else {
        const input = element(field.type === 'select' ? 'select' : 'input', undefined, label);
        input.required = true;
        if (field.type === 'select') {
          element('option', '请选择', input).value = '';
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
        inputs.push(input);
      }
      const input = inputs[0];
      if (!input) return;
      const read = () => multi ? inputs.filter(n => n.checked).map(n => n.value) : input.value.trim();
      function validateSelection(showError) {
        if (!multi) return true;
        const size = read().length;
        const requirement = lower === upper ? '请选择' + lower + '项' : '请选择' + lower + '～' + upper + '项';
        counter.textContent = '已选 ' + size + ' 项 · ' + requirement;
        const error = size < lower || size > upper ? requirement + '，当前已选' + size + '项。' : '';
        if (showError) {
          selectionError.textContent = error;
          input.setCustomValidity(error);
          label.setAttribute('aria-invalid', error ? 'true' : 'false');
        }
        return !error;
      }
      if (field.value !== null && field.value !== undefined && (!Array.isArray(field.value) || field.value.length)) {
        const previousValues = Array.isArray(field.value) ? field.value.map(String) : [String(field.value)];
        element('small', '上次填写：' + previousValues.join('、'), label).className = 'clarification-previous';
        if (multi) inputs.forEach(n => { n.checked = previousValues.includes(n.value); });
        else if (previousValues.length === 1 && (field.type !== 'select' || (field.options || []).includes(previousValues[0]))) input.value = previousValues[0];
      }
      if (field.message) {
        const reason = ['invalid', 'confirm'].includes(field.reason) ? field.reason : 'missing';
        const message = element('p', (reason === 'invalid' ? '需要修改：' : reason === 'confirm' ? '请确认：' : '') + field.message, label);
        message.className = 'clarification-reason ' + reason;
        message.id = 'clarification-reason-' + (++idSequence);
        inputs.forEach(n => n.setAttribute('aria-describedby', message.id));
        if (reason === 'invalid') input.setAttribute('aria-invalid', 'true');
      }
      if (field.hint) element('small', field.hint, label);
      inputs.forEach(n => n.addEventListener(multi ? 'change' : 'input', () => {
        input.setCustomValidity('');
        input.setAttribute('aria-invalid', 'false');
        validateSelection(true);
      }));
      validateSelection(false);
      controls.push({field, input, inputs, read, validateSelection});
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
      controls.forEach(item => { item.inputs.forEach(input => { input.disabled = disabled; }); });
      button.textContent = submitted ? '已提交' : submitting ? '正在提交…' : '继续查询';
      if (!active && !submitted) status.textContent = '此轮已结束，请在最新一轮继续补充。';
    }
    form.addEventListener('submit', async event => {
      event.preventDefault();
      if (submitted || submitting || !config.isCurrent() || !config.canSubmit()) return;
      for (const item of controls) {
        if (item.field.type === 'multiselect') item.validateSelection(true);
        else item.input.setCustomValidity(item.read() ? '' : '请填写' + item.field.label);
      }
      if (!form.reportValidity()) return;
      const question = '补充本轮查询条件：\n' + controls.map(item => item.field.label
        + (item.field.hint ? '（' + item.field.hint + '）' : '') + '：' + (Array.isArray(item.read()) ? item.read().join('、') : item.read())).join('\n');
      const answers = {};
      controls.forEach(item => { answers[item.field.key] = item.read(); });
      submitting = true;
      status.textContent = '';
      refresh();
      try {
        submitted = (await config.onSubmit(question, answers)) === true;
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
