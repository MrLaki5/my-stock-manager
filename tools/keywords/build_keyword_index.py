"""Builds app/src/main/assets/keyword_index.bin, the calibrated stock vocabulary the on-device keyword tagger ranks."""
import csv
import glob
import os
import re
import struct
import sys
import urllib.request

import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "..", "..", "app", "src", "main", "assets", "keyword_index.bin")
# Reviewed by hand: names, brands, named places, slang and niche terms agencies reject or the tagger guesses wrong.
EXCLUDE = os.path.join(HERE, "exclude.txt")
# The app keeps only the top two of these, so a dog photo is not filled with breed guesses.
BREEDS = os.path.join(HERE, "breeds.txt")
BREEDS_OUT = os.path.join(os.path.dirname(OUT), "keyword_breeds.txt")
CATS = ["Abstract", "Animals/Wildlife", "Arts", "Backgrounds/Textures", "Beauty/Fashion", "Buildings/Landmarks",
        "Business/Finance", "Celebrities", "Education", "Food and drink", "Healthcare/Medical", "Holidays",
        "Industrial", "Interiors", "Miscellaneous", "Nature", "Objects", "Parks/Outdoor", "People", "Religion",
        "Science", "Signs/Symbols", "Sports/Recreation", "Technology", "Transportation", "Vintage"]
VISUAL_NOUNS = {"noun.animal", "noun.artifact", "noun.body", "noun.food", "noun.object", "noun.person", "noun.plant",
                "noun.location", "noun.event", "noun.act", "noun.phenomenon", "noun.substance", "noun.shape",
                "noun.feeling", "noun.time", "noun.communication", "noun.group"}
EXTRA = ["elizabethan collar", "pet care", "veterinary care", "pour over coffee", "coffee brewing", "black dog",
         "cutting board", "fresh vegetables", "mountain landscape", "national park", "home interior", "wooden floor",
         "copy space", "close up", "top view", "nobody", "lifestyle", "concept", "background", "texture"]
# Common stock terms the WordNet filter misses; the bundled index was extended with these.
EXTRA += [l.strip() for l in open(os.path.join(HERE, "extra_terms.txt")) if l.strip()]


def fetch(url, path):
    if not os.path.exists(path):
        urllib.request.urlretrieve(url, path)
    return path


def build_vocab():
    import nltk
    os.makedirs("nltk_data", mode=0o700, exist_ok=True)
    for corpus in ("wordnet", "stopwords", "names"):
        nltk.download(corpus, quiet=True, download_dir="nltk_data")
    nltk.data.path.insert(0, "nltk_data")
    from nltk.corpus import names, stopwords, wordnet as wn
    from wordfreq import top_n_list, zipf_frequency

    stop = set(stopwords.words("english"))
    person_names = {n.lower() for n in names.words()}
    bad = {l.strip().lower() for l in open(fetch(
        "https://raw.githubusercontent.com/LDNOOBW/List-of-Dirty-Naughty-Obscene-and-Otherwise-Bad-Words/master/en",
        "badwords.txt")) if l.strip()}

    def ok_word(w):
        if len(w) < 3 or not w.isalpha() or w in stop or w in bad or w in person_names:
            return False
        syns = wn.synsets(w)
        if not syns or wn.morphy(w) != w:
            return False
        nominal = sum(1 for s in syns if s.pos() in ("n", "a", "s"))
        if nominal <= len(syns) - nominal:  # mostly a verb or adverb
            return False
        first = syns[0]
        if first.instance_hypernyms():  # proper noun: city, person, brand
            return False
        return first.lexname() in VISUAL_NOUNS if first.pos() == "n" else first.pos() in ("a", "s")

    words = [w for w in top_n_list("en", 80000) if ok_word(w)][:9000]
    phrases = set()
    classes = fetch("https://storage.googleapis.com/openimages/v7/oidv7-class-descriptions.csv", "oi_classes.csv")
    for row in csv.DictReader(open(classes)):
        p = row["DisplayName"].strip().lower()
        toks = p.split()
        if 2 <= len(toks) <= 3 and re.fullmatch(r"[a-z ]+", p) \
                and all(zipf_frequency(t, "en") >= 3.3 and t not in bad and t not in person_names for t in toks):
            phrases.add(p)
    return sorted((set(words) | phrases | set(EXTRA)) - excluded())


def excluded():
    if not os.path.exists(EXCLUDE):
        return set()
    return {l.strip() for l in open(EXCLUDE) if l.strip()}


def build_index():
    import open_clip
    import torch
    from PIL import Image
    torch.set_num_threads(os.cpu_count())
    model, _, pre = open_clip.create_model_and_transforms("ViT-B-16-SigLIP2", pretrained="webli")
    tok = open_clip.get_tokenizer("ViT-B-16-SigLIP2")

    terms = build_vocab()
    print(len(terms), "terms")
    with torch.no_grad():
        T = torch.cat([model.encode_text(tok([f"This is a photo of {t}." for t in terms[i:i + 256]]), normalize=True)
                       for i in range(0, len(terms), 256)]).numpy()
        C = model.encode_text(tok([f"This is a photo of {c.replace('/', ' and ').lower()}." for c in CATS]),
                              normalize=True).numpy()

    # Random stock-style photos; each term's mean and spread over them is its calibration.
    os.makedirs("calib", exist_ok=True)
    for k in range(1, 301):
        try:
            fetch(f"https://picsum.photos/seed/calib{k}/448/448", f"calib/{k}.jpg")
        except Exception:
            pass
    imgs = [p for p in sorted(glob.glob("calib/*.jpg")) if os.path.getsize(p) > 1000]
    with torch.no_grad():
        I = torch.cat([model.encode_image(pre(Image.open(p).convert("RGB")).unsqueeze(0), normalize=True)
                       for p in imgs]).numpy()
    S, SC = I @ T.T, I @ C.T
    np.savez("index.npz", terms=np.array(terms), T=T, mu=S.mean(0), sd=S.std(0) + 1e-6,
             cats=np.array(CATS), C=C, cmu=SC.mean(0), csd=SC.std(0) + 1e-6)


def export():
    z = np.load("index.npz")
    skip = excluded()
    keep = np.array([t not in skip for t in z["terms"]])
    terms, T = list(z["terms"][keep]), z["T"][keep].astype(np.float32)
    mu, sd = z["mu"][keep], z["sd"][keep]
    cats, C = list(z["cats"]), z["C"].astype(np.float32)
    scales = np.abs(T).max(1) / 127.0
    q = np.round(T / scales[:, None]).astype(np.int8)
    with open(OUT, "wb") as f:
        f.write(struct.pack("<5i", 0x5849574B, 1, T.shape[1], len(terms), len(cats)))
        for s in terms + cats:
            b = s.encode("utf-8")
            f.write(struct.pack("<H", len(b)) + b)
        f.write(q.tobytes())
        for a in (scales, mu, sd, C.reshape(-1), z["cmu"], z["csd"]):
            f.write(np.asarray(a, dtype="<f4").tobytes())
    print("wrote", os.path.abspath(OUT), os.path.getsize(OUT), "bytes,", len(terms), "terms")
    breeds = sorted({l.strip() for l in open(BREEDS) if l.strip()} & set(terms))
    open(BREEDS_OUT, "w").write("\n".join(breeds) + "\n")
    print("wrote", os.path.abspath(BREEDS_OUT), len(breeds), "breeds")


if __name__ == "__main__":
    if "--export" not in sys.argv:
        build_index()
    export()
