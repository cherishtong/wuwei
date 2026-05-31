# Crypto 加密能力

声明：`"crypto": {}`

## API

```js
// PBKDF2 密钥派生（600k 迭代，256-bit）
var key = capability.crypto.deriveKey("my-password", "random-salt");
// 返回: base64 编码的密钥字符串

// AES-256-GCM 加密（随机 12 字节 nonce）
var encrypted = capability.crypto.encrypt("敏感数据", key);
// 返回: base64(nonce + ciphertext)

// AES-256-GCM 解密
var plaintext = capability.crypto.decrypt(encrypted, key);
// 返回: 原始明文

// SHA-256 哈希
var hash = capability.crypto.hash("hello world");
// 返回: hex 字符串（64 字符）

// 随机字节生成（最大 1024）
var random = capability.crypto.randomBytes(32);
// 返回: base64 编码的随机字节

// 随机密码生成（大小写+数字+符号）
var password = capability.crypto.generatePassword(20);
// 返回: 随机密码字符串
```

## 安全限制

- `randomBytes` 上限 1024 字节
- 密钥和密文在日志中自动脱敏
- `deriveKey` 迭代次数固定在代码中（不可由技能传入）

## 使用场景

- 密码管理器
- API 密钥安全存储
- 数据加密/解密
- 哈希校验
