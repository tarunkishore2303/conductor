"""Independent evidence checks for live read-only copilot smoke responses."""
import re

UUID = re.compile(r'[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}', re.I)

def assert_copilot_evidence(response):
    if not isinstance(response, dict) or response.get('readOnly') is not True:
        raise ValueError('Copilot must explicitly be read-only')
    answer = response.get('answer')
    evidence = response.get('evidence')
    citations = response.get('evidenceIds')
    if not isinstance(answer, str) or not answer.strip() or len(answer) > 3000:
        raise ValueError('Invalid answer')
    if not isinstance(evidence, list) or len(evidence) > 4 or not isinstance(citations, list) or len(citations) > 4:
        raise ValueError('Invalid evidence bounds')
    if any(not isinstance(item, str) or not item for item in citations) or len(set(citations)) != len(citations):
        raise ValueError('Invalid citations')
    indexed = {}
    for item in evidence:
        if not isinstance(item, dict) or type(item.get('success')) is not bool:
            raise ValueError('Invalid evidence item')
        identifier = item.get('evidenceId')
        references = item.get('references')
        if not isinstance(identifier, str) or not identifier or identifier in indexed or not isinstance(references, list):
            raise ValueError('Invalid evidence identifier or references')
        for reference in references:
            if not isinstance(reference, dict) or not isinstance(reference.get('id'), str) or not UUID.fullmatch(reference['id']):
                raise ValueError('Invalid entity reference')
        indexed[identifier] = item
    if not set(citations) <= indexed.keys():
        raise ValueError('Unknown evidence citation')
    cited_success = [indexed[item] for item in citations if indexed[item]['success']]
    if any(item['success'] for item in evidence) and not cited_success:
        raise ValueError('Successful evidence must be cited')
    allowed = {reference['id'].lower() for item in cited_success for reference in item['references']}
    if any(identifier.lower() not in allowed for identifier in UUID.findall(answer)):
        raise ValueError('Answer contains an ungrounded entity identifier')
    return allowed
