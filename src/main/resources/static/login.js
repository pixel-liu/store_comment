(() => {
    'use strict';

    const { $, $$, requestJson, sessionKeys, pendingMealKey } = window.Campus;
    const params = new URLSearchParams(location.search);
    const form = $('#loginForm');
    const roles = $$('input[name="portal"]');
    const phoneInput = $('#phoneInput');
    const codeInput = $('#codeInput');
    const sendButton = $('#sendCodeButton');
    const loginButton = $('#loginButton');
    const codeHint = $('#codeHint');
    const loginError = $('#loginError');
    const next = params.get('next') || '';
    const batchId = params.get('meal') || '';
    const waitlistBatch = params.get('waitlist') || '';
    let cooldown = 0;
    let sendingCode = false;
    let signingIn = false;

    function destination(portal) {
        if (portal === 'backoffice') {
            return '/backoffice.html' + (['#windows', '#workbench'].includes(next) ? next : '');
        }
        const studentPage = /^#\/(?:reservations(?:\/[1-9]\d{0,18})?|window\/[1-9]\d{0,18}|waitlist)\/?$/;
        return studentPage.test(next) ? '/' + next : '/#/meals';
    }

    function updatePortal() {
        const portal = form.elements.portal.value;
        const backoffice = portal === 'backoffice';
        loginButton.textContent = backoffice ? '登录运营后台' : '登录学生端';
        $('#portalDescription').textContent = backoffice
            ? '窗口人员发布餐品、核销取餐，管理员管理食堂窗口和人员。'
            : '查看今日余量餐，预约后凭取餐码到窗口取餐。';
        const returnLink = $('#returnLink');
        returnLink.textContent = backoffice ? '返回运营后台' : '返回学生端';
        returnLink.href = destination(portal);
        loginError.textContent = '';
    }

    async function sendCode() {
        if (sendingCode || !phoneInput.reportValidity()) return;
        const phone = phoneInput.value;
        sendingCode = true;
        sendButton.disabled = true;
        codeHint.classList.remove('error');
        codeHint.textContent = '';
        try {
            const code = await requestJson(`/user/code?phone=${encodeURIComponent(phone)}`, { method: 'POST' });
            if (phone !== phoneInput.value) return;
            codeHint.textContent = code ? `开发环境验证码 ${code}` : '验证码已发送';
            codeInput.focus();
            const deadline = Date.now() + 60_000;
            sendButton.textContent = '60 秒后重试';
            cooldown = setInterval(() => {
                const seconds = Math.ceil((deadline - Date.now()) / 1000);
                sendButton.textContent = seconds > 0 ? `${seconds} 秒后重试` : '重新发送';
                if (seconds <= 0) {
                    clearInterval(cooldown);
                    cooldown = 0;
                    sendButton.disabled = signingIn;
                }
            }, 1000);
        } catch (error) {
            if (phone === phoneInput.value) {
                codeHint.classList.add('error');
                codeHint.textContent = error.message;
            }
        } finally {
            sendingCode = false;
            sendButton.disabled = Boolean(cooldown) || signingIn;
        }
    }

    async function login(event) {
        event.preventDefault();
        if (signingIn) return;
        const fields = Object.fromEntries(new FormData(form));
        signingIn = true;
        loginButton.disabled = true;
        sendButton.disabled = true;
        roles.forEach(role => { role.disabled = true; });
        loginError.textContent = '';
        try {
            const token = await requestJson('/user/login', { method: 'POST', body: JSON.stringify(fields) });
            localStorage.setItem(sessionKeys[fields.portal], token);
            const pendingMeal = sessionStorage.getItem(pendingMealKey);
            sessionStorage.removeItem(pendingMealKey);
            if (fields.portal === 'student') {
                if (pendingMeal === `login:${batchId}` && /^[1-9]\d{0,18}$/.test(batchId) &&
                    !destination('student').startsWith('/#/reservations')) {
                    sessionStorage.setItem(pendingMealKey, batchId);
                }
                if (pendingMeal === `login:waitlist:${waitlistBatch}` && /^[1-9]\d{0,18}$/.test(waitlistBatch) &&
                    destination('student') === '/#/waitlist') {
                    sessionStorage.setItem(pendingMealKey, `waitlist:${waitlistBatch}`);
                }
            }
            location.replace(destination(fields.portal));
        } catch (error) {
            loginError.textContent = error.message;
        } finally {
            signingIn = false;
            loginButton.disabled = false;
            sendButton.disabled = Boolean(cooldown) || sendingCode;
            roles.forEach(role => { role.disabled = false; });
        }
    }

    if (params.get('portal') === 'backoffice') form.elements.portal.value = 'backoffice';
    roles.forEach(role => role.addEventListener('change', updatePortal));
    phoneInput.addEventListener('input', () => {
        clearInterval(cooldown);
        cooldown = 0;
        sendButton.disabled = sendingCode || signingIn;
        sendButton.textContent = '获取验证码';
        codeInput.value = '';
        codeHint.textContent = '';
        loginError.textContent = '';
    });
    sendButton.addEventListener('click', sendCode);
    form.addEventListener('submit', login);
    updatePortal();
})();
