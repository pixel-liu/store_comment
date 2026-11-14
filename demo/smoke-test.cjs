const assert = require('node:assert/strict');
const { setTimeout: delay } = require('node:timers/promises');

const baseUrl = 'http://127.0.0.1:' + (process.env.FOODIEGO_DEMO_PORT || '8081');
const chinaTime = offset => new Date(Date.now() + (480 + offset) * 60_000).toISOString().slice(0, 19);

async function request(path, { token, method = 'GET', body } = {}) {
    const headers = {};
    if (token) headers.Authorization = token;
    if (body) headers['Content-Type'] = 'application/json';
    const response = await fetch(baseUrl + path, {
        method, headers, body: JSON.stringify(body), signal: AbortSignal.timeout(10_000)
    });
    const result = await response.json();
    assert(response.ok && result.success, `${method} ${path}: ${result.errorMsg || response.status}`);
    return result.data;
}

async function login(phone, portal) {
    const code = await request('/user/code?phone=' + phone, { method: 'POST' });
    assert.match(code, /^\d{6}$/);
    return request('/user/login', { method: 'POST', body: { phone, portal, code } });
}

async function waitForStatus(path, token, status) {
    for (let attempt = 0; attempt < 30; attempt++) {
        const data = await request(path, { token });
        if (data.status === status) return data;
        await delay(1000);
    }
    throw new Error(`Timed out waiting for ${status} at ${path}`);
}

async function main() {
    const admin = await login('19900009001', 'backoffice');
    const staff = await login('19900009002', 'backoffice');
    const student = await login('19900009003', 'student');
    const windows = await request('/backoffice/admin/windows', { token: admin });
    assert.equal(windows.length, 1);
    const shopId = windows[0].id;
    const staffWindows = await request('/staff/shops', { token: staff });
    assert(staffWindows.some(window => window.id === shopId));

    const staffPath = '/staff/shops/' + shopId;
    const batch = await request(staffPath + '/meal-batches', {
        token: staff,
        method: 'POST',
        body: {
            title: '黑椒鸡腿饭', stock: 1, beginTime: chinaTime(-5), endTime: chinaTime(20),
            pickupBeginTime: chinaTime(-2), pickupEndTime: chinaTime(35)
        }
    });
    const batchPath = staffPath + '/meal-batches/' + batch.id;
    await waitForStatus(batchPath, staff, 'OPEN');

    const order = await request('/meal-batches/' + batch.id + '/reservations', { token: student, method: 'POST' });
    const reservationPath = '/meal-reservations/' + order.id;
    const reservation = await waitForStatus(reservationPath, student, 'AWAITING_PICKUP');
    assert.match(reservation.pickupCode, /^\d{8}$/);
    await request(staffPath + reservationPath + '/redeem', {
        token: staff, method: 'POST', body: { pickupCode: reservation.pickupCode }
    });
    const completed = await request(reservationPath, { token: student });
    assert.equal(completed.status, 'REDEEMED');
    const progress = await request(batchPath, { token: staff });
    assert.equal(progress.redeemed, 1);
    assert.equal(progress.remaining, 0);
    assert.equal(progress.awaitingPickup, 0);
    process.stdout.write('Demo smoke test passed: publish, reserve, redeem.\n');
}

main().catch(error => {
    process.stderr.write(error.stack + '\n');
    process.exitCode = 1;
});
