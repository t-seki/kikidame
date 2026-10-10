# smbj（SMB の共有フォルダ、#198）が参照するが Android に無いクラス。R8 が「Missing class」で止まるので警告を抑える。
# どちらも使わない経路の参照: Kerberos（SpnegoAuthenticator が使う org.ietf.jgss）と、イベントバス mbassador の EL 式のフィルタ（javax.el）。
# アプリが使う認証は NTLM（ユーザー名とパスワード、またはゲスト）。Kerberos は対象外。
# 規則は AGP が出した app/build/outputs/mapping/release/missing_rules.txt のとおり。
-dontwarn javax.el.BeanELResolver
-dontwarn javax.el.ELContext
-dontwarn javax.el.ELResolver
-dontwarn javax.el.ExpressionFactory
-dontwarn javax.el.FunctionMapper
-dontwarn javax.el.ValueExpression
-dontwarn javax.el.VariableMapper
-dontwarn org.ietf.jgss.GSSContext
-dontwarn org.ietf.jgss.GSSCredential
-dontwarn org.ietf.jgss.GSSException
-dontwarn org.ietf.jgss.GSSManager
-dontwarn org.ietf.jgss.GSSName
-dontwarn org.ietf.jgss.Oid
