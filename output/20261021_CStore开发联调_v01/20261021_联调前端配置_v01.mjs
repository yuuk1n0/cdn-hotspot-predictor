import config from '../../frontend/vite.config.js'
config.server.port = 15173
config.server.strictPort = true
config.server.proxy['/api'].target = 'http://127.0.0.1:18080'
export default config
