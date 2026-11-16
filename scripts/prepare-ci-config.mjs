import {mkdir, writeFile} from 'node:fs/promises';

const supplied = process.env.GOOGLE_SERVICES_JSON;
// Deliberately invalid fallback: isolated compilation/device tests, never upload.
const config = supplied ? JSON.parse(supplied) : {
  project_info: {project_number: '123456789012', project_id: 'ci-not-a-firebase-project'},
  client: [{
    client_info: {
      mobilesdk_app_id: '1:123456789012:android:0000000000000000000000',
      android_client_info: {package_name: 'sk.bizagent.bleskovafaktura'},
    },
    oauth_client: [{client_id: 'ci-only.apps.googleusercontent.com', client_type: 3}],
    api_key: [{current_key: 'ci-not-a-real-firebase-api-key'}],
  }],
  configuration_version: '1',
};
if (!config.client?.some(c => c.client_info?.android_client_info?.package_name === 'sk.bizagent.bleskovafaktura')) {
  throw new Error('Firebase configuration does not contain the expected package.');
}
if (supplied && (config.project_info?.project_id !== 'gen-lang-client-0115180408' ||
    !config.client.some(c => c.client_info?.android_client_info?.package_name === 'sk.bizagent.bleskovafaktura' &&
      c.client_info?.mobilesdk_app_id === '1:356987646276:android:45325d3dea6c248380e711'))) {
  throw new Error('Real Firebase configuration must match the intended project and Android app ID.');
}
await mkdir('app', {recursive: true});
await writeFile('app/google-services.json', JSON.stringify(config));
console.log(supplied ? 'Real Firebase configuration prepared (values hidden).' : 'CI-only fixture: no live Firebase or sign-in proof.');
