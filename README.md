# TubeTube

Cliente de YouTube para Android focado em privacidade. É um fork do
[NewPipe](https://github.com/TeamNewPipe/NewPipe) oficial (baseado na v0.29.1) com um
**túnel de privacidade embutido**:

- **Sem anúncios e sem conta Google**, como o NewPipe.
- **Sem propaganda dentro dos vídeos:** patrocínios ("este vídeo é patrocinado por..."), autopromoção e
  pedidos de "se inscreva" são pulados automaticamente pelo [SponsorBlock](https://sponsor.ajay.app).
- **O Google não vê o seu IP.** Todo o tráfego do app sai por um túnel embutido:
  - **Tor**: anonimato máximo, sem depender de ninguém, mais lento;
  - **VPN WireGuard** (Mullvad, IVPN, Proton...): rápido, mas você confia no provedor.
  - **Direto** (opcional, você escolhe): sem túnel, o mais rápido. Continua sem anúncios e sem conta
    Google, mas o YouTube vê o seu IP.
- **Falha fechada** nos modos Tor e VPN: se o túnel cair, as requisições falham em vez de sair sem proteção.
- Funciona sozinho: não precisa de servidor próprio, do Orbot nem de app de VPN separado.

## Instalação

1. Abra a aba **Releases** deste repositório no celular e baixe o `TubeTube-vX.Y.Z.apk`.
2. Permita a instalação de fontes desconhecidas quando o Android pedir.
3. Abra o app e vá em **Configurações → Túnel de privacidade** para ver o status.

O padrão é o modo **Tor**. Na primeira abertura ele leva de alguns segundos a um minuto para conectar.
Nada carrega antes disso, e esse comportamento é intencional.

## Modo VPN WireGuard (rápido)

Exemplo com a Mullvad, que aceita conta sem e-mail e pagamento em dinheiro ou Monero:

1. Em mullvad.net, crie uma conta e vá em **WireGuard configuration**. Gere uma chave e baixe um arquivo `.conf` (um servidor perto de você deixa tudo mais rápido).
2. No TubeTube: **Configurações → Túnel de privacidade → Importar configuração (.conf)**.
3. Mude **Túnel** para **VPN WireGuard** e aceite o pedido de VPN do Android.

Só o TubeTube passa por essa VPN, o resto do celular não muda. Ao importar, o app reforça o arquivo:
força túnel completo para IPv4 e IPv6, adiciona DNS se faltar, e ignora qualquer lista de apps
que já esteja no arquivo.

## Como a privacidade funciona

| Caminho de rede | Como é protegido |
|---|---|
| Extractor e miniaturas (OkHttp) | `ProxySelector`, fábrica de sockets e DNS do túnel, mais um interceptor que recusa tudo com o túnel fora |
| Player do YouTube e downloads (`HttpURLConnection`) | `TunnelManager.openConnection()`: amarrado à rede da VPN, ou roteado pelo `ProxySelector` no Tor |
| Demais conexões `HttpURLConnection` (ex.: ExoPlayer genérico) | `ProxySelector` padrão do processo, instalado antes de qualquer acesso à rede |
| WebView da captcha ("não sou um robô") | `ProxyController` aponta as WebViews para o Tor. Sem suporte no aparelho, a página não abre |
| WebView do token anti-bot | Não acessa a rede (`blockNetworkLoads`). As requisições dela passam pelo OkHttp |

- **Tor:** o app usa a porta HTTP CONNECT do Tor. Os nomes de domínio são resolvidos pelo Tor, nunca pelo DNS do celular.
- **VPN:** os sockets do app são **amarrados à rede da VPN**. Se a VPN cair, eles falham em vez de escorregar para o Wi-Fi ou os dados móveis, mesmo nos milissegundos antes de o app perceber a queda.
- **Sem túnel:** toda conexão vai para um "buraco negro" local, que recusa na hora.

### Propaganda dentro dos vídeos (SponsorBlock)
- Por padrão o app pula **patrocínios, autopromoção e pedidos de interação**. Em
  **Configurações → Pular propagandas nos vídeos** dá para incluir abertura, encerramento, enrolação etc.
- A posição do vídeo é conferida a cada 0,2 s, então nem o começo da propaganda aparece.
- Se o vídeo **inteiro** estiver marcado como propaganda, o app avisa ao abrir.
- **Privacidade:** a consulta passa pelo túnel e envia só os 4 primeiros caracteres do SHA-256 do ID
  do vídeo. O servidor devolve os trechos de todos os vídeos com esse prefixo, sem saber qual você abriu.

### Importar playlists do YouTube (Takeout)
- **Configurações → Backup e restauração → Importar playlists do YouTube (Takeout)**. Escolha o
  `.zip` do Takeout inteiro (não precisa extrair) ou o `.csv` de uma playlist.
- Cada playlist vira uma playlist local, em **Playlists favoritas**. Os detalhes de cada vídeo são
  carregados pelo túnel; vídeos apagados ou privados são pulados. Playlists com o mesmo nome de uma
  que já existe não são importadas de novo.

### Aba Sugestões (calculada no aparelho)
- Adicione em **Configurações → Conteúdo → Conteúdo da tela inicial → + → Sugestões**.
- Parte dos últimos 10 vídeos do seu histórico (e, se o histórico for curto, de vídeos novos dos
  canais que você segue) e junta os "vídeos semelhantes" de cada um. Vídeos que aparecem para vários
  deles sobem, canais inscritos ganham um bônus, já assistidos e lives ficam de fora, no máximo 3 por canal.
- **Privacidade:** o histórico nunca sai do aparelho. As únicas requisições são as páginas dos
  vídeos, as mesmas que o app já abre, pelo túnel. Puxe para baixo para atualizar.
- As sugestões ficam guardadas no aparelho: a aba abre na hora com a última lista (sem os vídeos que
  você assistiu depois). Puxar para baixo calcula de novo.

### Atualização em segundo plano
- **Configurações → Conteúdo → Feed → Atualizar em segundo plano** (padrão: a cada 3 horas;
  dá para desligar ou escolher 1, 6, 12 ou 24 horas, e "Só no Wi-Fi").
- Periodicamente o app busca vídeos novos de todos os canais inscritos e prepara as sugestões,
  pelo túnel (ele espera o Tor ou a VPN conectar). Não roda com a bateria fraca.

### Ajustes de velocidade no Tor
- **Conflux:** junta dois caminhos que terminam na mesma saída. Mais banda, mesmo IP.
- **Circuitos mantidos por 30 minutos:** os links de vídeo do YouTube ficam presos ao IP que os pediu, e trocar a saída no meio do vídeo quebraria a reprodução.
- **Nova identidade Tor:** troca de saída quando a atual estiver lenta ou bloqueada.

Próximos passos planejados: ranking automático das saídas mais rápidas e que não levam bloqueio
de robô, e download em paralelo por vários circuitos com a mesma saída.

## Compilação e assinatura (GitHub Actions)

Todo push roda o workflow `Build TubeTube APK`, que gera o APK como *artifact*. Uma tag `v*`
(por exemplo `v0.29.1-tt1`) publica o APK em **Releases**.

Para que as atualizações instalem por cima da versão anterior, todo APK precisa ser assinado com a
**mesma chave**. Crie a sua uma única vez, num computador:

```sh
keytool -genkeypair -keystore tubetube.jks -storetype PKCS12 -alias tubetube \
  -keyalg RSA -keysize 4096 -validity 36500 -dname "CN=TubeTube"
base64 -w0 tubetube.jks > tubetube.jks.b64   # no macOS: base64 -i tubetube.jks -o tubetube.jks.b64
```

Em **Settings → Secrets and variables → Actions** do repositório, crie:
- `KEYSTORE_BASE64`: o conteúdo de `tubetube.jks.b64`
- `KEYSTORE_PASSWORD`: a senha escolhida

Guarde o `tubetube.jks` num lugar seguro e **nunca** faça commit dele: o repositório é público.
Sem esses secrets, o workflow ainda gera um APK assinado com uma chave descartável
(`-TEST-KEY.apk`), bom para testar mas que não aceita atualização por cima.

## Atualizar a partir do NewPipe

Quando o YouTube muda algo, a correção chega numa nova versão do NewPipe. O histórico do TubeTube
segue o do NewPipe, então atualizar é um merge:

```sh
git remote add upstream https://github.com/TeamNewPipe/NewPipe.git   # uma vez
git fetch upstream master
git merge upstream/master
```

As mudanças do TubeTube ficam concentradas em `app/src/main/java/org/schabi/newpipe/tunnel/` e em
poucas linhas de `DownloaderImpl`, `App`, `MainActivity`, `ReCaptchaActivity`,
`YoutubeHttpDataSource`, `DownloadMission`, nas configurações e no build, o que diminui os conflitos.

## Limitações conhecidas

- **O SponsorBlock é colaborativo:** só pula trechos que alguém já marcou. Vídeos populares são
  marcados rápido, mas um vídeo muito recente de um canal pequeno pode ainda não ter marcação.
  Produtos que aparecem na imagem não têm como ser removidos.
- No modo Tor, o YouTube às vezes bloqueia uma saída ("confirme que não é um robô"). Use **Nova identidade Tor**.
- Requisições `http://` sem TLS não passam pela porta do Tor e falham. É falha fechada, não vazamento.
- Links abertos no navegador externo ("abrir no navegador", "compartilhar") saem do TubeTube e não passam pelo túnel.

## Licença

GPL-3.0, como o NewPipe. Créditos ao Team NewPipe. A camada de túnel usa o
[tor-android](https://github.com/guardianproject/tor-android) do Guardian Project e o
[wireguard-android](https://git.zx2c4.com/wireguard-android/) (biblioteca `tunnel`).
