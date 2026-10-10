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

# smbj は SMBClient・Connection・Session の @Handler 付きメソッド（セッションの終了などの通知を受ける）を、
# イベントバス mbassador が注釈からリフレクションで呼ぶ（smbj の jar の @Handler の参照は確かめた）。コードからの参照が無いので R8 が消すおそれがある。
# 守らなかったときに実際に消えて困るかは確かめていない。予防の規則（実機の release ビルドの確認は docs/development.md の実機の確認項目）。
-keepattributes *Annotation*
-keepclassmembers class * {
    @net.engio.mbassy.listener.Handler <methods>;
}
