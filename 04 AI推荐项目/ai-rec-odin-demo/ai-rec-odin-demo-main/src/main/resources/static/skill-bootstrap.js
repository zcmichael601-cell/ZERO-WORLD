(function () {
  var finished = false;
  var timer;
  var panel = document.getElementById('startup-error');
  var details = document.getElementById('startup-error-details');

  function fail(message) {
    if (finished) return;
    clearTimeout(timer);
    panel.hidden = false;
    details.textContent = message;
    document.getElementById('model-label').textContent = '页面初始化未完成';
    document.getElementById('skill-estimate').textContent = '页面恢复后再读取预计耗时';
    document.getElementById('skill-select').innerHTML = '<option>页面加载异常，请重新加载</option>';
    document.getElementById('run-button').disabled = true;
  }

  function onError(event) {
    var target = event.target;
    if (target && target.tagName === 'SCRIPT') {
      fail('页面脚本加载失败，请检查网络或静态资源是否完整发布，再点击重新加载。');
    } else if (event.filename && /\.(m?js)(\?|$)/.test(event.filename)) {
      var filename = event.filename.split('/').pop().split('?')[0];
      fail('页面脚本执行异常：' + filename + '，行 ' + (event.lineno || '未知') + '。请重新加载；仍失败时请提供浏览器控制台的报错。');
    }
  }

  window.addEventListener('error', onError, true);
  window.addEventListener('skill-page-initialized', function () {
    finished = true;
    clearTimeout(timer);
    panel.hidden = true;
    window.removeEventListener('error', onError, true);
  }, { once: true });
  document.getElementById('startup-reload').addEventListener('click', function () {
    var url = new URL(window.location.href);
    url.searchParams.set('reload', String(Date.now()));
    window.location.replace(url.href);
  });
  timer = setTimeout(function () {
    fail('页面初始化超过 15 秒，可能是脚本未加载或接口未返回。请确认服务端已输出 SERVICE_READY，再重新加载；仍失败时请提供浏览器 Console / Network 报错。');
  }, 15000);
}());
