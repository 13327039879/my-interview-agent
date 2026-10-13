const error = document.querySelector('#login-error');
if (new URLSearchParams(location.search).has('error')) { error.textContent = '用户名或密码错误，请重试。'; error.hidden = false; }
try {
  const response = await fetch('/api/csrf');
  if (!response.ok) throw new Error();
  const csrf = await response.json();
  const input = document.createElement('input'); input.type = 'hidden'; input.name = csrf.parameterName; input.value = csrf.token;
  document.querySelector('#login-form').append(input);
  document.querySelector('#login-button').disabled = false;
} catch { error.textContent = '服务连接失败，请刷新页面重试。'; error.hidden = false; }
