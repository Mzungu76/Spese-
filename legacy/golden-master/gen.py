import random, datetime as dt, csv, openpyxl
random.seed(42)
rows=[]  # (date, valuta, amount, desc)
def add(d, a, desc, vd=None):
    rows.append((d, vd or d, round(a,2), desc))
start=dt.date(2024,1,1)
for m in range(1,11):
    base=dt.date(2024,m,1)
    add(base.replace(day=27), 1850.00, "BONIFICO SEPA DA DAG COSTRUZIONI SRL TRN 12345 PER STIPENDIO %02d/2024"%m)
    add(base.replace(day=3), -7.99, "Pagamento POS ILIAD ITALIA SPA %02d/03/2024 Op. 1234"%m)
    add(base.replace(day=5), -4.99, "Pagamento Google Pay GOOGLE PLAY %02d/05/2024 Op. 9999"%m)
    add(base.replace(day=10), -29.90 + (m%3)*0.5, "Pagamento POS NINTENDO EUROPE %02d/10/2024"%m)
    add(base.replace(day=2), -1.00, "Commissioni Prelievo ATM %02d/02/2024"%m)
    for i in range(random.randint(5,8)):
        d=base.replace(day=random.randint(1,28))
        shop=random.choice(["COOP ALLEANZA 3.0","EUROSPIN","CONAD SUPERSTORE","SUPERCASA"])
        add(d, -round(random.uniform(8,95),2), "Pagamento POS %s %s Op. %04d"%(shop,d.strftime("%d/%m/%Y"),random.randint(1,9999)))
    for i in range(random.randint(3,5)):
        d=base.replace(day=random.randint(1,28))
        add(d, -round(random.uniform(1.2,18),2), "Pagamento POS %s %s"%(random.choice(["BAR CENTRALE","PIZZERIA DA MARIO","GELATERIA SANETTI","TABACCHI N.5"]),d.strftime("%d/%m/%Y")))
    for i in range(random.randint(1,3)):
        d=base.replace(day=random.randint(1,28))
        add(d, -round(random.uniform(20,70),2), "Pagamento POS DISTRIBUTORE ENI %s"%d.strftime("%d/%m/%Y"))
    if m%2==0:
        d=base.replace(day=12); add(d, -round(random.uniform(15,120),2), "Pagamento E-Commerce AMAZON EU SARL %s"%d.strftime("%d/%m/%Y"))
    if m%3==0:
        d=base.replace(day=15); add(d, -round(random.uniform(25,60),2), "Pagamento POS FARMACIA COMUNALE %s"%d.strftime("%d/%m/%Y"))
    d=base.replace(day=20); add(d, -random.choice([50,100,150]), "Prelievo Bancomat ATM %s"%d.strftime("%d/%m/%Y"))
    d=base.replace(day=8); add(d, -round(random.uniform(40,200),2), "Pagamento POS XYZ SERVIZI SRL %s"%d.strftime("%d/%m/%Y"))
    if m in (1,4,7,10):
        add(base.replace(day=18), -85.30, "BOLLETTINO POSTALE ENEL ENERGIA")
        add(base.replace(day=16), 160.0, "ASSEGNO UNICO INPS %02d"%m)
# trasferimenti Revolut + due caffe identici + rimborso
add(dt.date(2024,3,14), -200.0, "Giroconto REVOLUT LTD ricarica")
add(dt.date(2024,5,14), -150.0, "Bonifico a HYPE ricarica")
add(dt.date(2024,6,9), 35.0, "Rimborso spese sanitarie")
add(dt.date(2024,6,10), -1.30, "Pagamento POS CAFFE ROMA 10/06/2024")
add(dt.date(2024,6,10), -1.30, "Pagamento POS CAFFE ROMA 10/06/2024")
rows.sort(key=lambda r:r[0], reverse=True)
# xlsx (Intesa-like with preamble)
wb=openpyxl.Workbook(); ws=wb.active; ws.title="Movimenti"
ws.append(["Lista movimenti"]); ws.append(["Conto: IT00X0000000000000000000000"]); ws.append([]); ws.append([])
ws.append(["Data Contabile","Data Valuta","Importo (euro)","Descrizione operazioni"])
for d,v,a,t in rows:
    ws.append([dt.datetime.combine(d,dt.time()), dt.datetime.combine(v,dt.time()), a, t])
for r in ws.iter_rows(min_row=6,min_col=1,max_col=2):
    for c in r: c.number_format="DD/MM/YYYY"
wb.save("intesa.xlsx")
# csv ; italian format second account
with open("revolut.csv","w",encoding="utf-8",newline="") as f:
    f.write("Data Contabile;Data Valuta;Importo (euro);Descrizione operazioni\n")
    for d,v,a,t in [(dt.date(2024,3,14),dt.date(2024,3,14),200.0,"Top-up da conto Intesa REVOLUT"),
                    (dt.date(2024,4,2),dt.date(2024,4,2),-45.5,"Pagamento POS AMAZON PRIME 02/04/2024"),
                    (dt.date(2024,5,14),dt.date(2024,5,14),150.0,"Ricarica HYPE da Intesa"),
                    (dt.date(2024,5,20),dt.date(2024,5,20),-1234.56,"Pagamento POS DECATHLON 20/05/2024")]:
        f.write("%s;%s;%s;%s\n"%(d.strftime("%d/%m/%Y"),v.strftime("%d/%m/%Y"),("%.2f"%a).replace(".",","),t))
print(len(rows),"rows")
